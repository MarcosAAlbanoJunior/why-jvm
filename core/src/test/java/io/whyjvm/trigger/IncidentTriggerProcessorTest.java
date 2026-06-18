package io.whyjvm.trigger;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.whyjvm.agent.AgentLoop;
import io.whyjvm.agent.StubLlmProvider;
import io.whyjvm.capture.EvidenceCapture;
import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.mcp.McpToolRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * O gatilho de SLOW vive no <b>limite do request</b>: so o span raiz e avaliado
 * contra o baseline do endpoint. Os spans-filho (queries, metodos) entram na
 * arvore do trace, mas nao disparam incidentes proprios — senao um unico request
 * com varias sub-operacoes lentas (N+1, ou o trafego sintetico de aquecimento)
 * viraria um alarme por sub-span.
 */
class IncidentTriggerProcessorTest {

    /** Captura falsa: so registra os incidentes que chegaram no disparo. */
    private static final class FakeCapture implements EvidenceCapture {
        final List<Incident> fired = new CopyOnWriteArrayList<>();

        @Override
        public Captured freeze(Incident incident) {
            fired.add(incident);
            IncidentRecord record = IncidentRecord.initial(
                    "id", Instant.now(), incident.endpoint(), incident.type(),
                    incident.fingerprint(), incident.threadName(), incident.durationMs(),
                    1, null, null, null);
            return new Captured(record, null);
        }

        @Override
        public IncidentRecord extract(Captured captured) {
            return captured.record();
        }
    }

    private static FakeCapture run(java.util.function.Consumer<Tracer> traffic) {
        FakeCapture capture = new FakeCapture();
        TriggerService trigger = new TriggerService(
                capture,
                new AgentLoop(new StubLlmProvider(), new McpToolRegistry()),
                laudo -> { },
                null,
                false);
        IncidentTriggerProcessor processor = new IncidentTriggerProcessor(
                trigger,
                new IncidentDeduplicator(Duration.ofMinutes(10)),
                new LatencyBaseline(3.0),
                new TraceSpanBuffer());
        try (SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(processor)
                .build()) {
            traffic.accept(tracerProvider.get("trigger-test"));
        }
        return capture;
    }

    /** Span raiz isolado (novo trace), com duracao exata em ms. */
    private static void emitRoot(Tracer tracer, String name, long durationMs) {
        Instant start = Instant.now();
        tracer.spanBuilder(name).setStartTimestamp(start).startSpan().end(start.plusMillis(durationMs));
    }

    /** Span raiz com um filho aninhado, cada um com duracao propria. */
    private static void emitRootWithChild(Tracer tracer, String rootName, long rootMs,
                                          String childName, long childMs) {
        Instant start = Instant.now();
        Span root = tracer.spanBuilder(rootName).setStartTimestamp(start).startSpan();
        try (Scope ignored = root.makeCurrent()) {
            tracer.spanBuilder(childName).setStartTimestamp(start).startSpan().end(start.plusMillis(childMs));
        } finally {
            root.end(start.plusMillis(rootMs));
        }
    }

    @Test
    void slowRootSpanFiresOneSlowIncident() {
        FakeCapture capture = run(tracer -> {
            // Aquece o baseline do endpoint com requests rapidos (> WARMUP amostras).
            for (int i = 0; i < 60; i++) {
                emitRoot(tracer, "GET /books", 1);
            }
            // Um request muito acima do p99: deve disparar SLOW.
            emitRoot(tracer, "GET /books", 100);
        });

        assertEquals(1, capture.fired.size(), "esperava um unico incidente SLOW no root");
        assertEquals(IncidentType.SLOW, capture.fired.get(0).type());
        assertEquals("GET /books", capture.fired.get(0).endpoint());
    }

    @Test
    void slowChildSpanDoesNotFire() {
        FakeCapture capture = run(tracer -> {
            // Aquece o baseline tanto do root quanto do filho com chamadas rapidas.
            for (int i = 0; i < 60; i++) {
                emitRootWithChild(tracer, "GET /books", 1, "SELECT books", 1);
            }
            // Root rapido, mas um filho lento: o sub-span NAO pode virar incidente.
            emitRootWithChild(tracer, "GET /books", 1, "SELECT books", 100);
        });

        assertEquals(0, capture.fired.size(),
                "sub-span lento sob um request rapido nao deve disparar incidente");
    }
}

package io.whyjvm.trigger;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
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
 * O gatilho de SLOW vive no <b>span de entrada do request</b> ({@link SpanKind#SERVER}):
 * so o span do servidor e avaliado contra o baseline do endpoint. Os spans-filho
 * (queries CLIENT, metodos INTERNAL) entram na arvore do trace, mas nao disparam
 * incidentes proprios — senao um unico request (N+1) viraria um alarme por sub-span.
 * O criterio e o <b>kind</b>, nao "ser raiz": um span SERVER continua valido mesmo
 * com pai (caso das chamadas internas de aquecimento).
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

    /** Span SERVER isolado (novo trace), com duracao exata em ms. */
    private static void emitServer(Tracer tracer, String name, long durationMs) {
        Instant start = Instant.now();
        tracer.spanBuilder(name).setSpanKind(SpanKind.SERVER)
                .setStartTimestamp(start).startSpan().end(start.plusMillis(durationMs));
    }

    /** Span SERVER com um filho CLIENT aninhado (ex.: a query do request). */
    private static void emitServerWithClientChild(Tracer tracer, String serverName, long serverMs,
                                                  String childName, long childMs) {
        Instant start = Instant.now();
        Span server = tracer.spanBuilder(serverName).setSpanKind(SpanKind.SERVER)
                .setStartTimestamp(start).startSpan();
        try (Scope ignored = server.makeCurrent()) {
            tracer.spanBuilder(childName).setSpanKind(SpanKind.CLIENT)
                    .setStartTimestamp(start).startSpan().end(start.plusMillis(childMs));
        } finally {
            server.end(start.plusMillis(serverMs));
        }
    }

    /** Span SERVER aninhado sob um span CLIENT (chamada interna: o warmer batendo na propria app). */
    private static void emitServerUnderClient(Tracer tracer, String serverName, long serverMs) {
        Instant start = Instant.now();
        Span client = tracer.spanBuilder("self-call").setSpanKind(SpanKind.CLIENT)
                .setStartTimestamp(start).startSpan();
        try (Scope ignored = client.makeCurrent()) {
            tracer.spanBuilder(serverName).setSpanKind(SpanKind.SERVER)
                    .setStartTimestamp(start).startSpan().end(start.plusMillis(serverMs));
        } finally {
            client.end(start.plusMillis(serverMs + 1));
        }
    }

    @Test
    void slowServerSpanFiresOneSlowIncident() {
        FakeCapture capture = run(tracer -> {
            for (int i = 0; i < 60; i++) {
                emitServer(tracer, "GET /books", 1); // aquece o baseline do endpoint
            }
            emitServer(tracer, "GET /books", 100); // muito acima do p99 -> SLOW
        });

        assertEquals(1, capture.fired.size(), "esperava um unico incidente SLOW no span SERVER");
        assertEquals(IncidentType.SLOW, capture.fired.get(0).type());
        assertEquals("GET /books", capture.fired.get(0).endpoint());
    }

    @Test
    void slowClientChildSpanDoesNotFire() {
        FakeCapture capture = run(tracer -> {
            for (int i = 0; i < 60; i++) {
                emitServerWithClientChild(tracer, "GET /books", 1, "SELECT books", 1);
            }
            // Request rapido, mas um filho CLIENT lento: o sub-span NAO vira incidente.
            emitServerWithClientChild(tracer, "GET /books", 1, "SELECT books", 100);
        });

        assertEquals(0, capture.fired.size(),
                "sub-span lento sob um request rapido nao deve disparar incidente");
    }

    @Test
    void serverSpanUnderClientStillWarmsAndFires() {
        // Regressao: chamadas internas (aquecimento) geram span SERVER COM pai. Elas
        // precisam alimentar o baseline; gatear por "sem pai" as deixaria de fora e
        // o N+1 externo nunca dispararia.
        FakeCapture capture = run(tracer -> {
            for (int i = 0; i < 60; i++) {
                emitServerUnderClient(tracer, "GET /books", 1);
            }
            emitServerUnderClient(tracer, "GET /books", 100);
        });

        assertEquals(1, capture.fired.size(),
                "span SERVER com pai (self-call) deve aquecer o baseline e disparar SLOW");
        assertEquals(IncidentType.SLOW, capture.fired.get(0).type());
    }
}

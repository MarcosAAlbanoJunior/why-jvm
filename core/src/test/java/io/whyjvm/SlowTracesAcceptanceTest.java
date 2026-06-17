package io.whyjvm;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.whyjvm.agent.AgentLoop;
import io.whyjvm.agent.StubLlmProvider;
import io.whyjvm.capture.EvidenceCapture;
import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.SlowTrace;
import io.whyjvm.mcp.McpToolRegistry;
import io.whyjvm.trigger.Incident;
import io.whyjvm.trigger.IncidentDeduplicator;
import io.whyjvm.trigger.IncidentTriggerProcessor;
import io.whyjvm.trigger.LatencyBaseline;
import io.whyjvm.trigger.TraceSpanBuffer;
import io.whyjvm.trigger.TriggerService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Aceitacao ponta a ponta do Tier 3: spans OTel reais (um request com N chamadas
 * identicas) passam pelo {@link IncidentTriggerProcessor} real, e a arvore do trace
 * montada no disparo (TraceSpanBuffer -> SlowTraceAssembler) chega ao {@link Incident}
 * com o N+1 detectado. Deterministico, sem JFR: dispara por ERROR (a montagem da
 * arvore independe do tipo do incidente).
 */
class SlowTracesAcceptanceTest {

    /** Captura falsa (sem JFR): so guarda o Incident que chegou no disparo. */
    private static final class FakeCapture implements EvidenceCapture {
        final List<Incident> fired = new CopyOnWriteArrayList<>();

        @Override
        public Captured freeze(Incident incident) {
            fired.add(incident);
            IncidentRecord record = IncidentRecord.initial(
                            "id", Instant.now(), incident.endpoint(), incident.type(),
                            incident.fingerprint(), incident.threadName(), incident.durationMs(),
                            1, null, null, null)
                    .withSlowTraces(incident.slowTraces());
            return new Captured(record, null);
        }

        @Override
        public IncidentRecord extract(Captured captured) {
            return captured.record();
        }
    }

    @Test
    void nPlusOneInTraceReachesTheIncident() {
        FakeCapture capture = new FakeCapture();
        TriggerService trigger = new TriggerService(
                capture,
                new AgentLoop(new StubLlmProvider(), new McpToolRegistry()),
                laudo -> { }, // sink no-op
                null);
        IncidentTriggerProcessor processor = new IncidentTriggerProcessor(
                trigger,
                new IncidentDeduplicator(Duration.ofMinutes(10)),
                new LatencyBaseline(3.0),
                new TraceSpanBuffer());

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(processor)
                .build();
        Tracer tracer = tracerProvider.get("acceptance");

        // Request "POST /checkout" com 12 queries identicas (N+1), terminando em erro.
        Span root = tracer.spanBuilder("POST /checkout").startSpan();
        try (Scope ignored = root.makeCurrent()) {
            for (int i = 0; i < 12; i++) {
                tracer.spanBuilder("SELECT orders").startSpan().end(); // filho do request
            }
            root.setStatus(StatusCode.ERROR, "falha apos as queries");
        } finally {
            root.end(); // dispara onEnd -> monta a arvore do trace
        }
        tracerProvider.close();

        assertEquals(1, capture.fired.size(), "esperava um incidente disparado");
        List<SlowTrace> traces = capture.fired.get(0).slowTraces();
        assertTrue(traces.stream().anyMatch(t -> t.span().equals("N+1: SELECT orders ×12")),
                "esperava o N+1 das 12 queries identicas: " + traces);
    }
}

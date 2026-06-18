package io.whyjvm.trigger;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.whyjvm.capture.SlowTrace;
import io.whyjvm.capture.SlowTraceAssembler;

import java.util.List;

/**
 * Portao 1 (o gatilho): ponto de instrumentacao mais limpo no mundo Java.
 *
 * <p>Todo span que termina passa por {@link #onEnd}, e ali temos status, duracao
 * e exceptions registradas no span. E barato porque e so uma checagem por
 * request, em memoria.
 *
 * <p>Detecta ERROR (status do span) e SLOW (latencia acima do baseline movel por
 * endpoint), com fingerprint/dedup/cooldown, e monta a arvore do trace no disparo.
 */
public final class IncidentTriggerProcessor implements SpanProcessor {

    private final TriggerService triggerService;
    private final IncidentDeduplicator dedup;
    private final LatencyBaseline baseline;
    private final TraceSpanBuffer spanBuffer;

    public IncidentTriggerProcessor(TriggerService triggerService, IncidentDeduplicator dedup,
                                    LatencyBaseline baseline, TraceSpanBuffer spanBuffer) {
        this.triggerService = triggerService;
        this.dedup = dedup;
        this.baseline = baseline;
        this.spanBuffer = spanBuffer;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        SpanData s = span.toSpanData();
        long durationNanos = s.getEndEpochNanos() - s.getStartEpochNanos();
        String traceId = s.getTraceId();
        String parentSpanId = s.getParentSpanContext().isValid() ? s.getParentSpanContext().getSpanId() : null;
        boolean isRoot = parentSpanId == null;

        // Retem TODO span para montar a arvore do trace no disparo (Tier 3): os
        // filhos fecham antes do root, entao precisam ser guardados antes de saber
        // se o trace vira incidente.
        spanBuffer.record(traceId, s.getSpanId(), parentSpanId, s.getName(), durationNanos);

        boolean isError = s.getStatus().getStatusCode() == StatusCode.ERROR;
        long durationMs = durationNanos / 1_000_000;

        IncidentType type = classify(isRoot, s.getName(), isError, durationMs);
        if (type == null) {
            evictIfRoot(isRoot, traceId); // trace normal terminou: libera o buffer.
            return;
        }

        String fingerprint = Fingerprints.of(s.getName(), type, s);

        // Controle de tempestade: uma investigacao por fingerprint a cada
        // cooldown. As outras N falhas so incrementam o contador (custo zero).
        if (!dedup.shouldFire(fingerprint)) {
            evictIfRoot(isRoot, traceId);
            return;
        }

        // Monta a arvore com os spans retidos do trace (e remove do buffer).
        List<SlowTrace> slowTraces = SlowTraceAssembler.assemble(spanBuffer.collectAndEvict(traceId));

        // onEnd roda na propria thread do request (no span.end()): captura o nome.
        triggerService.fire(new Incident(s.getName(), type, durationMs, fingerprint,
                Thread.currentThread().getName(), s, slowTraces));
    }

    /** Root sem incidente: o trace terminou, descarta o buffer. Span filho: o root limpa depois. */
    private void evictIfRoot(boolean isRoot, String traceId) {
        if (isRoot) {
            spanBuffer.evict(traceId);
        }
    }

    /**
     * Natureza do incidente, ou {@code null} se o request e normal. Erros sempre
     * disparam; lentidao so para requests bem-sucedidos — nao poluir o baseline
     * de latencia com a duracao de requests que falharam.
     *
     * <p>SLOW so e avaliado no <b>span raiz</b> (o limite do request): a lentidao
     * do endpoint contra o baseline dele. Os spans-filho (queries, metodos,
     * commits) entram na arvore do trace que explica a causa, mas nao disparam
     * incidentes proprios — senao um unico request (ex.: N+1) viraria um alarme
     * por sub-span lento, e o trafego sintetico de aquecimento idem.
     */
    private IncidentType classify(boolean isRoot, String endpoint, boolean isError, long durationMs) {
        if (isError) {
            return IncidentType.ERROR;
        }
        if (!isRoot) {
            return null;
        }
        return baseline.isAnomalousAndRecord(endpoint, durationMs) ? IncidentType.SLOW : null;
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        // nada: a decisao acontece no fim do span.
    }

    @Override
    public boolean isStartRequired() {
        return false;
    }
}

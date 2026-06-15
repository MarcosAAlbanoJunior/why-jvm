package io.whyjvm.trigger;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * Portao 1 (o gatilho): ponto de instrumentacao mais limpo no mundo Java.
 *
 * <p>Todo span que termina passa por {@link #onEnd}, e ali temos status, duracao
 * e exceptions registradas no span. E barato porque e so uma checagem por
 * request, em memoria.
 *
 * <p>Fase 0: detecta apenas ERROR. Fase 1 adiciona fingerprint/dedup/cooldown.
 * Fase 3 adiciona deteccao de lentidao por baseline movel.
 */
public final class IncidentTriggerProcessor implements SpanProcessor {

    private final TriggerService triggerService;
    private final IncidentDeduplicator dedup;
    private final LatencyBaseline baseline;

    public IncidentTriggerProcessor(TriggerService triggerService, IncidentDeduplicator dedup,
                                    LatencyBaseline baseline) {
        this.triggerService = triggerService;
        this.dedup = dedup;
        this.baseline = baseline;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        SpanData s = span.toSpanData();
        boolean isError = s.getStatus().getStatusCode() == StatusCode.ERROR;
        long durationMs = (s.getEndEpochNanos() - s.getStartEpochNanos()) / 1_000_000;

        IncidentType type = classify(s.getName(), isError, durationMs);
        if (type == null) {
            return; // request normal: nada a investigar.
        }

        String fingerprint = Fingerprints.of(s.getName(), type, s);

        // Controle de tempestade: uma investigacao por fingerprint a cada
        // cooldown. As outras N falhas so incrementam o contador (custo zero).
        if (!dedup.shouldFire(fingerprint)) {
            return;
        }

        triggerService.fire(new Incident(s.getName(), type, durationMs, fingerprint, s));
    }

    /**
     * Natureza do incidente, ou {@code null} se o request e normal. Erros sempre
     * disparam; lentidao so para requests bem-sucedidos — nao poluir o baseline
     * de latencia com a duracao de requests que falharam.
     */
    private IncidentType classify(String endpoint, boolean isError, long durationMs) {
        if (isError) {
            return IncidentType.ERROR;
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

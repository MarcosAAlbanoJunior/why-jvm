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

    public IncidentTriggerProcessor(TriggerService triggerService, IncidentDeduplicator dedup) {
        this.triggerService = triggerService;
        this.dedup = dedup;
    }

    @Override
    public void onEnd(ReadableSpan span) {
        SpanData s = span.toSpanData();
        boolean isError = s.getStatus().getStatusCode() == StatusCode.ERROR;

        // TODO Fase 3: boolean isSlow = baseline.isAnomalous(s.getName(), durationMs);
        long durationMs = (s.getEndEpochNanos() - s.getStartEpochNanos()) / 1_000_000;

        if (!isError) {
            return;
        }

        IncidentType type = IncidentType.ERROR;
        String fingerprint = Fingerprints.of(s.getName(), type, s);

        // Controle de tempestade: uma investigacao por fingerprint a cada
        // cooldown. As outras N falhas so incrementam o contador (custo zero).
        if (!dedup.shouldFire(fingerprint)) {
            return;
        }

        triggerService.fire(new Incident(s.getName(), type, durationMs, fingerprint, s));
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

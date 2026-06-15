package io.whyjvm.trigger;

import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * Um incidente recem-detectado pelo gatilho, antes da captura de evidencia.
 *
 * <p>Carrega o {@link SpanData} cru do span que disparou para que a captura
 * extraia dele a exception, a duracao e o endpoint, e o {@code fingerprint}
 * (identidade do incidente) ja computado pelo gatilho.
 */
public record Incident(String endpoint, IncidentType type, long durationMs, String fingerprint, SpanData span) {
}

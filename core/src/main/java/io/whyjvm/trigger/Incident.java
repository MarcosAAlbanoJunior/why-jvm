package io.whyjvm.trigger;

import io.opentelemetry.sdk.trace.data.SpanData;

/**
 * Um incidente recem-detectado pelo gatilho, antes da captura de evidencia.
 *
 * <p>Carrega o {@link SpanData} cru do span que disparou para que a captura
 * extraia dele a exception, a duracao e o endpoint.
 */
public record Incident(String endpoint, IncidentType type, long durationMs, SpanData span) {
}

package io.whyjvm.trigger;

import io.opentelemetry.sdk.trace.data.SpanData;
import io.whyjvm.capture.SlowTrace;

import java.util.List;

/**
 * Um incidente recem-detectado pelo gatilho, antes da captura de evidencia.
 *
 * <p>Carrega o {@link SpanData} cru do span que disparou para que a captura
 * extraia dele a exception, a duracao e o endpoint, o {@code fingerprint}
 * (identidade do incidente) ja computado pelo gatilho, e o {@code threadName}
 * da thread que atendeu o request (o gatilho roda nela, no fim do span) — usado
 * para atribuir os eventos JFR da janela aquela thread especifica.
 *
 * <p>{@code slowTraces} e a arvore do trace ja montada no disparo (Tier 3), a
 * partir dos spans retidos pelo {@link TraceSpanBuffer} — nao vem do JFR.
 */
public record Incident(String endpoint, IncidentType type, long durationMs, String fingerprint,
                       String threadName, SpanData span, List<SlowTrace> slowTraces) {
}

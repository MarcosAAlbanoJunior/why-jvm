package io.whyjvm.capture;

import io.whyjvm.trigger.Incident;

import java.nio.file.Path;

/**
 * Captura de evidencia em duas fases, refletindo o portao 2 e o endurecimento
 * 5.5#1:
 *
 * <ol>
 *   <li>{@link #freeze} — <b>sincrono</b>, na thread do request: congela a janela
 *       JFR imediatamente (antes que o buffer circular gire) e grava o registro
 *       inicial. So o congelamento, barato e critico para nao perder evidencia.</li>
 *   <li>{@link #extract} — <b>fora da thread do request</b>: parseia o snapshot
 *       congelado, monta os agregados e enriquece o registro. E o trabalho pesado;
 *       roda no executor single-thread do {@code TriggerService} (single-flight).</li>
 * </ol>
 */
public interface EvidenceCapture {

    /** Resultado do congelamento: o registro inicial e o caminho do snapshot (ou null). */
    record Captured(IncidentRecord record, Path jfr) {
    }

    /** Sincrono: congela a janela agora e grava o registro inicial. */
    Captured freeze(Incident incident);

    /**
     * Fora da thread do request: extrai os agregados do snapshot congelado,
     * enriquece e regrava o registro. Sem snapshot, devolve o registro inalterado.
     */
    IncidentRecord extract(Captured captured);
}

package io.whyjvm.capture;

import io.whyjvm.trigger.Incident;

/**
 * Congela a janela de evidencia no instante do disparo do gatilho.
 *
 * <p>A sutileza central: o JFR roda num buffer circular com idade maxima. Se
 * esperarmos o agente rodar para ler os eventos, o buffer ja girou e a
 * evidencia sumiu. Por isso a captura acontece <b>agora</b>, sincronamente,
 * antes de qualquer chamada ao agente.
 */
public interface EvidenceCapture {

    /**
     * Captura (congela) a evidencia do incidente e devolve o registro duravel.
     */
    IncidentRecord capture(Incident incident);
}

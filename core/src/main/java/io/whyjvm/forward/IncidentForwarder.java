package io.whyjvm.forward;

import io.whyjvm.capture.IncidentRecord;

/**
 * Fronteira do split (Fase 5): para onde o incidente capturado vai depois da
 * extracao. No <b>modo split</b>, o Java nao roda o agente — ele encaminha o
 * {@link IncidentRecord} (ja com os agregados) para o servico de analise em Go,
 * que investiga e despacha. No modo simples (sem forwarder), o agente roda
 * in-process.
 *
 * <p>A unica coisa que cruza a fronteira Java→Go e o {@code IncidentRecord} JSON;
 * nenhuma credencial de LLM viaja (a key vive no ambiente do servico Go).
 */
public interface IncidentForwarder {

    /** Encaminha o incidente para o servico de analise. Nao deve lancar. */
    void forward(IncidentRecord record);
}

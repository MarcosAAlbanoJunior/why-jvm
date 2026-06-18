package io.whyjvm.trigger;

import io.whyjvm.agent.AgentLoop;
import io.whyjvm.agent.Laudo;
import io.whyjvm.capture.EvidenceCapture;
import io.whyjvm.capture.EvidenceCapture.Captured;
import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.forward.IncidentForwarder;
import io.whyjvm.sink.Sink;

import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Orquestra o fluxo a partir do disparo: <b>captura sincrona</b> (congela a
 * evidencia agora, antes que o buffer JFR gire) e, fora da thread do request, a
 * <b>extracao</b> + o destino.
 *
 * <p>Dois destinos, decididos pela presenca de um {@link IncidentForwarder}:
 * <ul>
 *   <li><b>Split:</b> com forwarder, o Java <b>encaminha</b> o registro
 *       ao servico de analise em Go (que investiga e despacha). O Java nao roda o
 *       agente — leve, sobrevive ao OOM, sem key de LLM.</li>
 *   <li><b>Modo simples:</b> sem forwarder, o agente investiga in-process e o
 *       {@link Sink} publica o laudo.</li>
 * </ul>
 */
public final class TriggerService {

    private static final Logger LOG = Logger.getLogger(TriggerService.class.getName());

    private final EvidenceCapture capture;
    private final AgentLoop agent;
    private final Sink sink;
    private final IncidentForwarder forwarder; // null = modo simples (agente in-process)
    private final boolean retainEvidence; // false = apaga o .jfr apos extrair (nada mais o le)
    private final ExecutorService investigators;

    public TriggerService(EvidenceCapture capture, AgentLoop agent, Sink sink,
                          IncidentForwarder forwarder, boolean retainEvidence) {
        this.capture = capture;
        this.agent = agent;
        this.sink = sink;
        this.forwarder = forwarder;
        this.retainEvidence = retainEvidence;
        this.investigators = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "whyjvm-investigator");
            t.setDaemon(true);
            return t;
        });
    }

    public void fire(Incident incident) {
        // Congelar AGORA, sincronamente (so o freeze, barato).
        Captured captured = capture.freeze(incident);
        LOG.info("Incidente congelado: " + captured.record().incidentId()
                + " (" + captured.record().endpoint() + ")");

        // Fora da thread do request: extracao pesada do JFR + destino. O executor e
        // single-thread, entao a extracao e naturalmente single-flight — uma
        // tempestade de incidentes nao dispara N parses concorrentes.
        investigators.submit(() -> {
            try {
                IncidentRecord record = capture.extract(captured);
                if (forwarder != null) {
                    forwarder.forward(record); // split: investigacao roda no servico Go
                } else {
                    Laudo laudo = agent.investigate(record); // modo simples: agente in-process
                    sink.publish(laudo);
                }
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Falha ao processar incidente "
                        + captured.record().incidentId(), e);
            } finally {
                discardEvidence(captured);
            }
        });
    }

    /**
     * Apaga o snapshot JFR depois do uso: a extracao ja leu tudo para o
     * {@link IncidentRecord} e nada mais abre o .jfr. Mantido em disco apenas se
     * {@code retainEvidence} (forense manual).
     */
    private void discardEvidence(Captured captured) {
        if (retainEvidence || captured.jfr() == null) {
            return;
        }
        try {
            Files.deleteIfExists(captured.jfr());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Falha ao apagar snapshot JFR " + captured.jfr(), e);
        }
    }
}

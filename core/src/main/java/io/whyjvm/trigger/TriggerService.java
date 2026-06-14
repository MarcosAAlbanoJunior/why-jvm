package io.whyjvm.trigger;

import io.whyjvm.agent.AgentLoop;
import io.whyjvm.agent.Laudo;
import io.whyjvm.capture.EvidenceCapture;
import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.sink.Sink;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Orquestra o fluxo a partir do disparo: <b>captura sincrona</b> (congela a
 * evidencia agora, antes que o buffer JFR gire) e <b>investigacao assincrona</b>
 * (o agente analisa o registro congelado sem pressa, fora da thread do request).
 */
public final class TriggerService {

    private static final Logger LOG = Logger.getLogger(TriggerService.class.getName());

    private final EvidenceCapture capture;
    private final AgentLoop agent;
    private final Sink sink;
    private final ExecutorService investigators;

    public TriggerService(EvidenceCapture capture, AgentLoop agent, Sink sink) {
        this.capture = capture;
        this.agent = agent;
        this.sink = sink;
        this.investigators = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "whyjvm-investigator");
            t.setDaemon(true);
            return t;
        });
    }

    public void fire(Incident incident) {
        // Portao 2 comeca aqui: congelar AGORA, sincronamente.
        IncidentRecord record = capture.capture(incident);
        LOG.info("Incidente capturado: " + record.incidentId() + " (" + record.endpoint() + ")");

        // Investigacao fora da thread do request: nao penalizar a app.
        investigators.submit(() -> {
            try {
                Laudo laudo = agent.investigate(record);
                sink.publish(laudo);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Falha ao investigar incidente " + record.incidentId(), e);
            }
        });
    }
}

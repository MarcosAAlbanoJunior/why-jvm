package io.whyjvm.capture;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.whyjvm.trigger.Incident;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementacao da captura via JFR programatico.
 *
 * <p>No startup abre uma recording continua num buffer rotativo (idade maxima).
 * No disparo, {@code takeSnapshot()} tira uma foto instantanea do que esta no
 * buffer; capturamos agora, analisamos depois.
 */
public final class JfrEvidenceCapture implements EvidenceCapture {

    private static final Logger LOG = Logger.getLogger(JfrEvidenceCapture.class.getName());

    // Chaves semanticas do OTel para o evento de exception anexado ao span.
    private static final AttributeKey<String> EXC_TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> EXC_MESSAGE = AttributeKey.stringKey("exception.message");
    private static final AttributeKey<String> EXC_STACK = AttributeKey.stringKey("exception.stacktrace");

    private final Path incidentDir;
    private final IncidentStore store;
    private Recording rolling;

    public JfrEvidenceCapture(Path incidentDir, IncidentStore store) {
        this.incidentDir = incidentDir;
        this.store = store;
        startRollingRecording();
    }

    /** Recording continua, configurada uma vez. Buffer rotativo de 5 minutos. */
    private void startRollingRecording() {
        try {
            Configuration config = Configuration.getConfiguration("profile");
            rolling = new Recording(config);
            rolling.setMaxAge(Duration.ofMinutes(5));
            rolling.setToDisk(true);
            rolling.start();
            LOG.info("JFR rolling recording iniciada (maxAge=5min).");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Nao foi possivel iniciar JFR; evidencia seguira sem snapshot.", e);
        }
    }

    @Override
    public IncidentRecord capture(Incident incident) {
        String incidentId = newIncidentId(incident);

        Path jfr = dumpWindow(incidentId);
        SpanData span = incident.span();

        ExceptionDetails exc = extractException(span);

        IncidentRecord record = new IncidentRecord(
                incidentId,
                Instant.now(),
                incident.endpoint(),
                incident.type(),
                incident.fingerprint(),
                incident.threadName(),
                incident.durationMs(),
                exc.type(),
                exc.message(),
                exc.stackTrace(),
                jfr
        );

        store.save(record);
        return record;
    }

    /**
     * No disparo: congela a janela imediatamente, antes de chamar o agente.
     *
     * <p>Usa {@code rolling.dump()} em vez de {@code FlightRecorder.takeSnapshot()}:
     * o snapshot so enxerga chunks ja rotacionados para o repositorio em disco e
     * por isso vinha vazio logo apos o disparo. O dump forca a escrita do chunk
     * ativo, garantindo a evidencia recente da janela.
     */
    private Path dumpWindow(String incidentId) {
        if (rolling == null) {
            return null; // JFR nao iniciou no ambiente; segue sem snapshot.
        }
        try {
            Files.createDirectories(incidentDir);
            Path out = incidentDir.resolve(incidentId + ".jfr");
            rolling.dump(out);
            if (Files.size(out) == 0) {
                Files.deleteIfExists(out);
                return null;
            }
            return out;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Falha ao gravar snapshot JFR do incidente " + incidentId, e);
            return null;
        }
    }

    private static ExceptionDetails extractException(SpanData span) {
        for (EventData event : span.getEvents()) {
            if ("exception".equals(event.getName())) {
                return new ExceptionDetails(
                        event.getAttributes().get(EXC_TYPE),
                        event.getAttributes().get(EXC_MESSAGE),
                        event.getAttributes().get(EXC_STACK)
                );
            }
        }
        return new ExceptionDetails(null, null, null);
    }

    private static String newIncidentId(Incident incident) {
        String slug = incident.endpoint().replaceAll("[^a-zA-Z0-9]+", "-");
        return slug + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private record ExceptionDetails(String type, String message, String stackTrace) {
    }
}

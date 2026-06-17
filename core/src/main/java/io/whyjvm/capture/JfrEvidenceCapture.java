package io.whyjvm.capture;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.whyjvm.trigger.Incident;
import jdk.jfr.Recording;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Implementacao da captura via JFR programatico, em duas fases (ver
 * {@link EvidenceCapture}).
 *
 * <p>No startup abre uma recording continua num buffer rotativo (idade maxima).
 * No disparo, {@link #freeze} grava (congela) a janela; a leitura/agregacao do
 * snapshot fica para {@link #extract}, fora da thread do request.
 */
public final class JfrEvidenceCapture implements EvidenceCapture {

    private static final Logger LOG = Logger.getLogger(JfrEvidenceCapture.class.getName());

    // Chaves semanticas do OTel para o evento de exception anexado ao span.
    private static final AttributeKey<String> EXC_TYPE = AttributeKey.stringKey("exception.type");
    private static final AttributeKey<String> EXC_MESSAGE = AttributeKey.stringKey("exception.message");
    private static final AttributeKey<String> EXC_STACK = AttributeKey.stringKey("exception.stacktrace");

    /** Idade e tamanho maximos do buffer rotativo (5.5#3: limita por ambos). */
    private static final Duration MAX_AGE = Duration.ofMinutes(5);
    private static final long MAX_SIZE_BYTES = 50L * 1024 * 1024;
    /** Threshold das esperas: abaixo disto e ruido (e overhead). */
    private static final Duration WAIT_THRESHOLD = Duration.ofMillis(10);

    private final Path incidentDir;
    private final IncidentStore store;
    private final CodeContextResolver codeContext; // null = code-aware desligado
    private Recording rolling;

    public JfrEvidenceCapture(Path incidentDir, IncidentStore store) {
        this(incidentDir, store, null);
    }

    public JfrEvidenceCapture(Path incidentDir, IncidentStore store, CodeContextResolver codeContext) {
        this.incidentDir = incidentDir;
        this.store = store;
        this.codeContext = codeContext;
        startRollingRecording();
    }

    /** Recording continua, configurada uma vez. Buffer rotativo por idade E tamanho. */
    private void startRollingRecording() {
        try {
            rolling = new Recording(); // sem config de prateleira: habilita so o necessario
            enableEvidenceEvents(rolling);
            rolling.setMaxAge(MAX_AGE);
            rolling.setMaxSize(MAX_SIZE_BYTES);
            rolling.setToDisk(true);
            rolling.start();
            LOG.info("JFR rolling recording iniciada (config enxuta, maxAge=5min, maxSize=50MB).");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Nao foi possivel iniciar JFR; evidencia seguira sem snapshot.", e);
        }
    }

    /**
     * Config enxuta (5.5#3): habilita SO os eventos que o {@link EvidenceExtractor}
     * le, em vez do firehose do config {@code profile} (method sampling a 10ms,
     * centenas de eventos). Corta overhead em throughput alto. Pacote: visivel para
     * teste.
     */
    static void enableEvidenceEvents(Recording r) {
        // Dimensoes JVM-wide.
        r.enable("jdk.GarbageCollection");
        r.enable("jdk.ObjectAllocationSample").withStackTrace();
        r.enable("jdk.JavaMonitorEnter").withStackTrace().withThreshold(WAIT_THRESHOLD);
        r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(20)); // mais espacado que o profile (10ms)
        // Atividade da thread do request: espera (sleep/I/O/park) vs trabalho (CPU).
        r.enable("jdk.ThreadSleep").withStackTrace().withThreshold(WAIT_THRESHOLD);
        r.enable("jdk.ThreadPark").withStackTrace().withThreshold(WAIT_THRESHOLD);
        r.enable("jdk.SocketRead").withThreshold(WAIT_THRESHOLD);
        r.enable("jdk.SocketWrite").withThreshold(WAIT_THRESHOLD);
        r.enable("jdk.FileRead").withThreshold(WAIT_THRESHOLD);
        r.enable("jdk.FileWrite").withThreshold(WAIT_THRESHOLD);
    }

    @Override
    public Captured freeze(Incident incident) {
        Instant capturedAt = Instant.now();
        String incidentId = newIncidentId(incident);

        // Congela a janela AGORA, sincronamente, antes que o buffer JFR gire.
        Path jfr = dumpWindow(incidentId);

        ExceptionDetails exc = extractException(incident.span());
        ExceptionInfo exception = exc.isPresent()
                ? new ExceptionInfo(exc.type(), exc.message(), exc.stackTrace())
                : null;

        IncidentRecord record = IncidentRecord.initial(
                incidentId, capturedAt, incident.endpoint(), incident.type(),
                incident.fingerprint(), incident.threadName(), incident.durationMs(),
                1, exception, null, null)
                .withSlowTraces(incident.slowTraces()); // arvore do trace (Tier 3), montada no disparo

        store.save(record);
        return new Captured(record, jfr);
    }

    @Override
    public IncidentRecord extract(Captured captured) {
        // Code-aware (Tier 2): resolve o fonte do frame suspeito a partir do stack da
        // exception. Independe do JFR — um ERROR sem snapshot ainda tem stack a apontar.
        IncidentRecord record = withCodeContext(captured.record());

        Path jfr = captured.jfr();
        if (jfr == null) {
            return record; // JFR nao gerou evidencia desta janela.
        }
        try {
            EvidenceExtractor.Result r = EvidenceExtractor.extract(
                    jfr, record.capturedAt(), record.durationMs(), record.threadName());
            IncidentRecord enriched = record.withEvidence(r.signals(), r.dimensions(), jfr.toString());
            store.save(enriched);
            return enriched;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Falha ao extrair evidencia JFR do incidente " + record.incidentId(), e);
            return record;
        }
    }

    /** Anexa o code context quando ha resolver e algo concreto a apontar; senao, o registro inalterado. */
    private IncidentRecord withCodeContext(IncidentRecord record) {
        if (codeContext == null) {
            return record;
        }
        try {
            CodeContext cc = codeContext.forIncident(record);
            if (cc == null) {
                return record;
            }
            IncidentRecord enriched = record.withCodeContext(cc);
            store.save(enriched);
            return enriched;
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Falha ao resolver code context do incidente " + record.incidentId(), e);
            return record;
        }
    }

    /**
     * No disparo: congela a janela imediatamente. Usa {@code rolling.dump()} (e nao
     * {@code FlightRecorder.takeSnapshot()}): o snapshot global so enxerga chunks ja
     * rotacionados para o disco e por isso vinha vazio logo apos o disparo; o dump
     * forca a escrita do chunk ativo, garantindo a evidencia recente da janela.
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
        boolean isPresent() {
            return type != null || message != null || stackTrace != null;
        }
    }
}

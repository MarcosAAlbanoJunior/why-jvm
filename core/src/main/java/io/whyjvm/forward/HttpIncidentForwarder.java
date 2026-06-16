package io.whyjvm.forward;

import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.IncidentRecordJson;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Encaminha o incidente ao servico de analise em Go por HTTP, com <b>padrao
 * outbox</b>: grava o JSON num diretorio local <b>antes</b> de tentar o POST.
 * Se o POST falha (Go fora do ar, justo quando a app esta em apuros), o arquivo
 * fica no outbox e um varredor em background reenvia depois. Nenhum incidente se
 * perde.
 *
 * <p>O reenvio e seguro porque o lado Go dedupa por {@code incidentId} (idempotente):
 * reenviar o mesmo incidente e no-op la.
 *
 * <p>Usa apenas o {@code java.net.http} do JDK — sem dependencia nova no core.
 */
public final class HttpIncidentForwarder implements IncidentForwarder, AutoCloseable {

    private static final Logger LOG = Logger.getLogger(HttpIncidentForwarder.class.getName());
    private static final Duration POST_TIMEOUT = Duration.ofSeconds(10);
    private static final long RETRY_PERIOD_SECONDS = 30;

    private final URI endpoint;
    private final String token;
    private final Path outboxDir;
    private final HttpClient http;
    private final ScheduledExecutorService retry;

    public HttpIncidentForwarder(String baseUrl, String token, Path outboxDir) {
        this(baseUrl, token, outboxDir, HttpClient.newHttpClient(), true);
    }

    /** Construtor de teste: HttpClient injetavel e varredor opcional. */
    HttpIncidentForwarder(String baseUrl, String token, Path outboxDir, HttpClient http, boolean startSweeper) {
        this.endpoint = URI.create(baseUrl.replaceAll("/+$", "") + "/v1/incidents");
        this.token = token;
        this.outboxDir = outboxDir;
        this.http = http;
        try {
            Files.createDirectories(outboxDir);
        } catch (Exception e) {
            throw new IllegalStateException("Nao foi possivel criar o outbox em " + outboxDir, e);
        }
        if (startSweeper) {
            this.retry = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "whyjvm-forward-retry");
                t.setDaemon(true);
                return t;
            });
            retry.scheduleWithFixedDelay(this::flushOutbox,
                    RETRY_PERIOD_SECONDS, RETRY_PERIOD_SECONDS, TimeUnit.SECONDS);
        } else {
            this.retry = null;
        }
    }

    @Override
    public void forward(IncidentRecord record) {
        Path file = outboxDir.resolve(safeName(record.incidentId()) + ".json");
        try {
            String json = IncidentRecordJson.toJson(record);
            // Durabilidade: grava ANTES de tentar mandar.
            Files.writeString(file, json, StandardCharsets.UTF_8);
            if (post(json)) {
                Files.deleteIfExists(file);
                LOG.info("Incidente encaminhado ao servico de analise: " + record.incidentId());
            } else {
                LOG.warning("Servico de analise indisponivel; incidente " + record.incidentId()
                        + " ficou no outbox para reenvio.");
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Falha ao encaminhar incidente " + record.incidentId()
                    + " (segue no outbox, se gravado).", e);
        }
    }

    /** Reenvia os incidentes pendentes no outbox. Pacote: visivel para teste. */
    void flushOutbox() {
        try (Stream<Path> files = Files.list(outboxDir)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(this::resend);
        } catch (Exception e) {
            LOG.log(Level.FINE, "Falha ao varrer o outbox", e);
        }
    }

    private void resend(Path file) {
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            if (post(json)) {
                Files.deleteIfExists(file);
                LOG.info("Incidente pendente reenviado: " + file.getFileName());
            }
        } catch (Exception e) {
            LOG.log(Level.FINE, "Reenvio falhou para " + file.getFileName() + "; tenta de novo depois.", e);
        }
    }

    /** POST do JSON. true se o servico aceitou (2xx). */
    private boolean post(String json) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .timeout(POST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (token != null && !token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<Void> resp = http.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() / 100 == 2;
        } catch (Exception e) {
            return false; // rede/Go fora: fica no outbox
        }
    }

    /** Nome de arquivo seguro: o incidentId tem ':' (timestamp), invalido no Windows. */
    private static String safeName(String incidentId) {
        return URLEncoder.encode(incidentId, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        if (retry != null) {
            retry.shutdownNow();
        }
    }
}

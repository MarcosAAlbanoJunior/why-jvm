package io.whyjvm.forward;

import com.sun.net.httpserver.HttpServer;
import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.trigger.IncidentType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpIncidentForwarderTest {

    private static IncidentRecord sampleRecord() {
        // id com ':' (timestamp) exercita o escape do nome de arquivo no Windows.
        return IncidentRecord.initial(
                "2026-06-15T14:03:07Z-checkout-d4e5f6", Instant.now(), "POST /checkout",
                IncidentType.SLOW, "fp-1", "http-nio-1", 4200, 1, null, null, null);
    }

    private static long outboxCount(Path dir) throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".json")).count();
        }
    }

    @Test
    void forwardsThenClearsOutbox(@TempDir Path outbox) throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> auth = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/incidents", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();
        try (HttpIncidentForwarder fwd = new HttpIncidentForwarder(
                "http://localhost:" + server.getAddress().getPort(), "test-token",
                outbox, HttpClient.newHttpClient(), false)) {

            fwd.forward(sampleRecord());

            assertTrue(body.get().contains("2026-06-15T14:03:07Z-checkout-d4e5f6"),
                    "POST deveria carregar o incidente: " + body.get());
            assertEquals("Bearer test-token", auth.get());
            assertEquals(0, outboxCount(outbox), "outbox deveria estar vazio apos sucesso");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void keepsInOutboxWhenServiceDownThenResends(@TempDir Path outbox) throws Exception {
        AtomicBoolean down = new AtomicBoolean(true);
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/incidents", exchange -> {
            byte[] in = exchange.getRequestBody().readAllBytes();
            int code = down.get() ? 503 : 202;
            if (code == 202) {
                body.set(new String(in, StandardCharsets.UTF_8));
            }
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
        server.start();
        try (HttpIncidentForwarder fwd = new HttpIncidentForwarder(
                "http://localhost:" + server.getAddress().getPort(), null,
                outbox, HttpClient.newHttpClient(), false)) {

            // Go "fora": o incidente fica no outbox, nao se perde.
            fwd.forward(sampleRecord());
            assertEquals(1, outboxCount(outbox), "incidente deveria ficar no outbox com o servico fora");

            // Go "volta": o varredor reenvia e limpa o outbox.
            down.set(false);
            fwd.flushOutbox();
            assertEquals(0, outboxCount(outbox), "outbox deveria limpar apos reenvio");
            assertTrue(body.get() != null && body.get().contains("checkout"),
                    "o reenvio deveria entregar o incidente");
        } finally {
            server.stop(0);
        }
    }
}

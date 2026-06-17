package io.whyjvm.otel;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prova a cadeia zero-codigo: o agente OTel (aqui, o {@code AutoConfiguredOpenTelemetrySdk})
 * descobre a extensao via SPI, ela registra o {@code SpanProcessor} sincrono, e um span
 * ERROR vira um incidente encaminhado ao servico de analise — sem o app tocar em nada.
 */
class WhyJvmAutoConfigTest {

    @Test
    void registeredViaServiceLoader() {
        boolean found = false;
        for (AutoConfigurationCustomizerProvider p : ServiceLoader.load(AutoConfigurationCustomizerProvider.class)) {
            if (p instanceof WhyJvmAutoConfig) {
                found = true;
            }
        }
        assertTrue(found, "WhyJvmAutoConfig deveria estar registrada via META-INF/services");
    }

    @Test
    void errorSpanIsForwardedToAnalysisService() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/incidents", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        server.start();

        Path incidentDir = Files.createTempDirectory("whyjvm-otel-ext");
        OpenTelemetrySdk sdk = AutoConfiguredOpenTelemetrySdk.builder()
                .addPropertiesSupplier(() -> Map.of(
                        "otel.traces.exporter", "none",
                        "otel.metrics.exporter", "none",
                        "otel.logs.exporter", "none",
                        "whyjvm.forward.url", "http://localhost:" + server.getAddress().getPort(),
                        "whyjvm.incident.dir", incidentDir.toString()))
                .build()
                .getOpenTelemetrySdk();

        try {
            Tracer tracer = sdk.getTracer("test");
            Span span = tracer.spanBuilder("GET /demo/x").startSpan();
            span.setStatus(StatusCode.ERROR, "boom");
            span.end(); // -> onEnd sincrono -> incidente -> forward (async)

            // O forward roda fora da thread (extracao + POST); aguarda a chegada.
            long deadline = System.currentTimeMillis() + 10_000;
            while (body.get() == null && System.currentTimeMillis() < deadline) {
                Thread.sleep(100);
            }

            assertNotNull(body.get(), "o incidente deveria ter sido encaminhado ao /v1/incidents");
            assertTrue(body.get().contains("GET /demo/x"), body.get());
            assertTrue(body.get().contains("ERROR"), body.get());
        } finally {
            sdk.close();
            server.stop(0);
            deleteQuietly(incidentDir);
        }
    }

    /** Best-effort: no Windows o .jfr async pode segurar o arquivo no momento da limpeza. */
    private static void deleteQuietly(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // arquivo em uso; o SO limpa o temp depois
                }
            });
        } catch (IOException ignored) {
            // diretorio ja sumiu / em uso
        }
    }
}

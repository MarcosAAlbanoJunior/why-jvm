package io.whyjvm.sample;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.whyjvm.WhyJvm;
import io.whyjvm.sink.LoggingSink;
import io.whyjvm.sink.Sink;
import io.whyjvm.sink.email.EmailSink;
import io.whyjvm.sink.email.SmtpConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Liga o why-jvm na app: monta o {@link WhyJvm} (defaults da Fase 0 — stub
 * provider + sink de log) e registra o seu SpanProcessor no SdkTracerProvider.
 *
 * <p>Para usar IA de verdade, troque aqui:
 * <pre>
 *   WhyJvm.builder()
 *       .llmProvider(new ClaudeProvider(System.getenv("LLM_API_KEY")))
 *       .sink(new SlackSink(System.getenv("SLACK_WEBHOOK")))
 *       .build();
 * </pre>
 *
 * <p>O sink de e-mail liga sozinho se as variaveis SMTP estiverem no ambiente
 * (ver {@link #emailSinkFromEnv()}); senao, cai no {@link LoggingSink}.
 */
@Configuration
public class WhyJvmConfig {

    private static final Logger LOG = Logger.getLogger(WhyJvmConfig.class.getName());

    @Bean
    public WhyJvm whyJvm() {
        return WhyJvm.builder()
                .incidentDir(Path.of("incidents"))
                .sink(emailSinkFromEnv())
                .build();
    }

    /**
     * Monta o {@link EmailSink} a partir do ambiente (BYOK), ou cai no
     * {@link LoggingSink} se nao houver config SMTP. Variaveis:
     * <pre>
     *   WHYJVM_SMTP_HOST   ex: smtp.gmail.com
     *   WHYJVM_SMTP_PORT   default 587
     *   WHYJVM_SMTP_USER   usuario SMTP
     *   WHYJVM_SMTP_PASS   senha / app password
     *   WHYJVM_MAIL_FROM   remetente
     *   WHYJVM_MAIL_TO     destinatarios, separados por virgula
     * </pre>
     */
    private static Sink emailSinkFromEnv() {
        String host = System.getenv("WHYJVM_SMTP_HOST");
        String from = System.getenv("WHYJVM_MAIL_FROM");
        String to = System.getenv("WHYJVM_MAIL_TO");
        if (host == null || from == null || to == null) {
            LOG.info("SMTP nao configurado (WHYJVM_SMTP_HOST/MAIL_FROM/MAIL_TO); usando LoggingSink.");
            return new LoggingSink();
        }
        String portEnv = System.getenv("WHYJVM_SMTP_PORT");
        int port = portEnv != null ? Integer.parseInt(portEnv) : 587;
        List<String> recipients = Arrays.stream(to.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        SmtpConfig config = new SmtpConfig(
                host, port,
                System.getenv("WHYJVM_SMTP_USER"),
                System.getenv("WHYJVM_SMTP_PASS"),
                from, recipients,
                port == 587);
        LOG.info("EmailSink ativo: laudos irao para " + recipients);
        return new EmailSink(config);
    }

    @Bean
    public Tracer tracer(WhyJvm whyJvm) {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(whyJvm.spanProcessor())
                .build();

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build();

        return sdk.getTracer("io.whyjvm.sample");
    }
}

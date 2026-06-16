package io.whyjvm.sample;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.whyjvm.WhyJvm;
import io.whyjvm.agent.LlmProvider;
import io.whyjvm.forward.HttpIncidentForwarder;
import io.whyjvm.llm.LlmProviders;
import io.whyjvm.sink.LoggingSink;
import io.whyjvm.sink.Sink;
import io.whyjvm.sink.email.EmailSink;
import io.whyjvm.sink.email.SmtpConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Liga o why-jvm na app: monta o {@link WhyJvm} e registra o seu SpanProcessor
 * no SdkTracerProvider.
 *
 * <p>A config (chaves do Gemini e SMTP) vem do {@link Environment} do Spring, que
 * resolve a mesma chave de duas fontes: um arquivo local fora do git
 * ({@code why-jvm-local.properties}, importado em {@code application.properties})
 * <b>ou</b> variavel de ambiente (binding relaxado: {@code llm.api-key} casa com
 * {@code LLM_API_KEY}, {@code whyjvm.smtp.host} com {@code WHYJVM_SMTP_HOST}...).
 * Sem config, cai nos defaults (StubLlmProvider + LoggingSink).
 */
@Configuration
public class WhyJvmConfig {

    private static final Logger LOG = Logger.getLogger(WhyJvmConfig.class.getName());

    @Bean
    public WhyJvm whyJvm(Environment env) {
        WhyJvm.Builder builder = WhyJvm.builder()
                .incidentDir(Path.of("incidents"))
                .sink(emailSink(env));

        // Modo split (Fase 5): se whyjvm.forward.url (WHYJVM_FORWARD_URL) estiver
        // setada, o Java encaminha o incidente para o servico de analise em Go e
        // NAO roda o agente in-process. Sem ela, cai no modo simples (agente local).
        String forwardUrl = env.getProperty("whyjvm.forward.url");
        if (forwardUrl != null && !forwardUrl.isBlank()) {
            builder.forwarder(new HttpIncidentForwarder(
                    forwardUrl,
                    env.getProperty("whyjvm.forward.token"),
                    Path.of("incidents", "outbox")));
            LOG.info("Modo split: incidentes vao para " + forwardUrl
                    + " (o agente roda no servico Go; o Java nao usa key de LLM).");
        } else {
            LlmProvider provider = LlmProviders.create(
                    env.getProperty("llm.provider"),
                    env.getProperty("llm.api-key"),
                    env.getProperty("llm.model"));
            if (provider != null) {
                builder.llmProvider(provider);
            } else {
                LOG.info("llm.api-key (ou LLM_API_KEY) ausente; usando StubLlmProvider (laudo placeholder).");
            }
        }
        return builder.build();
    }

    /**
     * Monta o {@link EmailSink} a partir da config, ou cai no {@link LoggingSink}
     * se faltar SMTP. Chaves (no arquivo local OU como env var equivalente):
     * <pre>
     *   whyjvm.smtp.host   (WHYJVM_SMTP_HOST)   ex: smtp.gmail.com
     *   whyjvm.smtp.port   (WHYJVM_SMTP_PORT)   default 587
     *   whyjvm.smtp.user   (WHYJVM_SMTP_USER)
     *   whyjvm.smtp.pass   (WHYJVM_SMTP_PASS)   senha / app password
     *   whyjvm.mail.from   (WHYJVM_MAIL_FROM)
     *   whyjvm.mail.to     (WHYJVM_MAIL_TO)     destinatarios separados por virgula
     * </pre>
     */
    private static Sink emailSink(Environment env) {
        String host = env.getProperty("whyjvm.smtp.host");
        String from = env.getProperty("whyjvm.mail.from");
        String to = env.getProperty("whyjvm.mail.to");
        if (host == null || from == null || to == null) {
            LOG.info("SMTP nao configurado (whyjvm.smtp.host / mail.from / mail.to); usando LoggingSink.");
            return new LoggingSink();
        }
        int port = env.getProperty("whyjvm.smtp.port", Integer.class, 587);
        List<String> recipients = Arrays.stream(to.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        SmtpConfig config = new SmtpConfig(
                host, port,
                env.getProperty("whyjvm.smtp.user"),
                env.getProperty("whyjvm.smtp.pass"),
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

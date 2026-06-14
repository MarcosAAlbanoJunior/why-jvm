package io.whyjvm.sample;

import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.whyjvm.WhyJvm;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

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
 */
@Configuration
public class WhyJvmConfig {

    @Bean
    public WhyJvm whyJvm() {
        return WhyJvm.builder()
                .incidentDir(Path.of("incidents"))
                .build();
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

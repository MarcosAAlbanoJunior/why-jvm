package io.whyjvm.otel;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.whyjvm.WhyJvm;

/**
 * Extensao do agente OpenTelemetry: registra o {@code SpanProcessor} do why-jvm no
 * tracer provider do agente, sem o app tocar em uma linha de codigo (zero-codigo).
 * Descoberta via SPI ({@code META-INF/services}).
 *
 * <p>O processor entra <b>SINCRONO</b> ({@code addSpanProcessor} direto, nunca
 * {@code BatchSpanProcessor}): o gatilho roda no {@code onEnd} na thread do request,
 * e e dali que a captura atribui a atividade JFR aquela thread. Num batch processor
 * o {@code onEnd} rodaria noutra thread e a atribuicao se perderia.
 */
public final class WhyJvmAutoConfig implements AutoConfigurationCustomizerProvider {

    @Override
    public void customize(AutoConfigurationCustomizer autoConfiguration) {
        autoConfiguration.addTracerProviderCustomizer((tracerProviderBuilder, config) -> {
            WhyJvm whyJvm = WhyJvmFromConfig.build(config);
            return tracerProviderBuilder.addSpanProcessor(whyJvm.spanProcessor());
        });
    }
}

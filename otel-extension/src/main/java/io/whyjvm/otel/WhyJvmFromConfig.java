package io.whyjvm.otel;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import io.whyjvm.WhyJvm;
import io.whyjvm.forward.HttpIncidentForwarder;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Monta o {@link WhyJvm} a partir da config do agente OTel ({@link ConfigProperties}).
 * Le as chaves {@code whyjvm.*} do OTel — as mesmas que qualquer app usaria — sem
 * depender de Spring nem de wiring no codigo da aplicacao.
 *
 * <p>Com {@code whyjvm.forward.url} setada, vai para o <b>modo split</b>: so captura
 * e encaminha o incidente ao servico de analise (o LLM roda la). Sem ela, cai no
 * default leve do core ({@code LoggingSink} + {@code StubLlmProvider}) — degrada
 * honesto, sem puxar dependencia extra para dentro do app.
 */
public final class WhyJvmFromConfig {

    private WhyJvmFromConfig() {
    }

    public static WhyJvm build(ConfigProperties config) {
        String incidentDir = config.getString("whyjvm.incident.dir", "incidents");

        WhyJvm.Builder builder = WhyJvm.builder()
                .incidentDir(Path.of(incidentDir))
                .cooldown(config.getDuration("whyjvm.cooldown", Duration.ofMinutes(10)))
                .slowThreshold(config.getDouble("whyjvm.slow.threshold", 3.0))
                .retainEvidence(config.getBoolean("whyjvm.evidence.retain", false));

        // Code-aware (Tier 2): pacotes da app e diretorios de fonte, app-agnostico.
        List<String> appPackages = config.getList("whyjvm.app.packages");
        if (!appPackages.isEmpty()) {
            builder.appBasePackages(appPackages.toArray(String[]::new));
        }
        List<String> sourceDirs = config.getList("whyjvm.source.dirs");
        if (!sourceDirs.isEmpty()) {
            builder.sourceDirs(sourceDirs.stream().map(Path::of).toArray(Path[]::new));
        }

        // Split: encaminha ao servico Go (sem rodar LLM no app).
        String forwardUrl = config.getString("whyjvm.forward.url");
        if (forwardUrl != null && !forwardUrl.isBlank()) {
            Path outbox = Path.of(incidentDir, "outbox");
            builder.forwarder(new HttpIncidentForwarder(
                    forwardUrl, config.getString("whyjvm.forward.token"), outbox));
        }
        return builder.build();
    }
}

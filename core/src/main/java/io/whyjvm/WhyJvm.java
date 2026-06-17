package io.whyjvm;

import io.opentelemetry.sdk.trace.SpanProcessor;
import io.whyjvm.agent.AgentLoop;
import io.whyjvm.agent.LlmProvider;
import io.whyjvm.agent.StubLlmProvider;
import io.whyjvm.capture.CodeContextResolver;
import io.whyjvm.capture.EvidenceCapture;
import io.whyjvm.capture.InMemoryIncidentStore;
import io.whyjvm.capture.IncidentStore;
import io.whyjvm.capture.JfrEvidenceCapture;
import io.whyjvm.capture.SourceResolver;
import io.whyjvm.forward.IncidentForwarder;
import io.whyjvm.mcp.McpToolRegistry;
import io.whyjvm.mcp.tools.GetAllocationHotspotsTool;
import io.whyjvm.mcp.tools.GetExceptionDetailsTool;
import io.whyjvm.mcp.tools.GetGcActivityTool;
import io.whyjvm.mcp.tools.GetLockContentionTool;
import io.whyjvm.mcp.tools.GetThreadActivityTool;
import io.whyjvm.mcp.tools.TriageTool;
import io.whyjvm.sink.LoggingSink;
import io.whyjvm.sink.Sink;
import io.whyjvm.trigger.IncidentDeduplicator;
import io.whyjvm.trigger.IncidentTriggerProcessor;
import io.whyjvm.trigger.LatencyBaseline;
import io.whyjvm.trigger.TriggerService;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Fachada de montagem do why-jvm. Liga gatilho -> captura -> tools -> agente ->
 * sink e expoe o {@link SpanProcessor} que voce registra no seu
 * {@code SdkTracerProvider}.
 *
 * <p>Use o {@link Builder} para escolher provider de IA e sink (as duas
 * fronteiras de extensao). Os defaults fecham o circuito da Fase 0 sem
 * precisar de key.
 */
public final class WhyJvm {

    private final SpanProcessor spanProcessor;

    private WhyJvm(SpanProcessor spanProcessor) {
        this.spanProcessor = spanProcessor;
    }

    /** Registre isto no seu SdkTracerProvider para ligar o gatilho. */
    public SpanProcessor spanProcessor() {
        return spanProcessor;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private Path incidentDir = Path.of("incidents");
        private IncidentStore store;
        private LlmProvider provider = new StubLlmProvider();
        private Sink sink = new LoggingSink();
        private int maxToolCalls = 8;
        private Duration cooldown = Duration.ofMinutes(10);
        private double slowFactor = 3.0;
        private IncidentForwarder forwarder;
        private boolean codeAware = true;
        private List<String> appBasePackages = List.of();
        private List<Path> sourceDirs = List.of();
        private int sourceContextLines = SourceResolver.DEFAULT_CONTEXT_LINES;

        public Builder incidentDir(Path dir) {
            this.incidentDir = dir;
            return this;
        }

        public Builder store(IncidentStore store) {
            this.store = store;
            return this;
        }

        /** Fronteira de IA: troque o stub por Claude/Gemini (BYOK). */
        public Builder llmProvider(LlmProvider provider) {
            this.provider = provider;
            return this;
        }

        /** Fronteira de saida: troque o log por Slack/WhatsApp/webhook. */
        public Builder sink(Sink sink) {
            this.sink = sink;
            return this;
        }

        public Builder maxToolCalls(int maxToolCalls) {
            this.maxToolCalls = maxToolCalls;
            return this;
        }

        /** Janela de cooldown do controle de tempestade (default: 10 minutos). */
        public Builder cooldown(Duration cooldown) {
            this.cooldown = cooldown;
            return this;
        }

        /** Fator sobre o p99 movel que marca um request como lento (default: 3x). */
        public Builder slowThreshold(double factor) {
            this.slowFactor = factor;
            return this;
        }

        /**
         * Modo split (Fase 5): encaminha o incidente para o servico de analise em
         * Go em vez de rodar o agente in-process. Sem forwarder, fica no modo
         * simples (agente + sink locais).
         */
        public Builder forwarder(IncidentForwarder forwarder) {
            this.forwarder = forwarder;
            return this;
        }

        /**
         * Code-aware RCA (Tier 2): anexa ao laudo o fonte do metodo suspeito. Ligado
         * por default — degrada honesto para "fonte indisponivel" quando nao acha o
         * fonte. Desligue se nao quiser a resolucao de fonte na captura.
         */
        public Builder codeAware(boolean enabled) {
            this.codeAware = enabled;
            return this;
        }

        /**
         * Pacotes-raiz da app monitorada (ex.: "com.acme"), para distinguir o frame da
         * app do de framework/JDK no stack. Sem isto, usa um exclude-list de infra.
         */
        public Builder appBasePackages(String... packages) {
            this.appBasePackages = List.of(packages);
            return this;
        }

        /** Diretorios de fonte da app (dev/CI), lidos quando o fonte nao esta no classpath. */
        public Builder sourceDirs(Path... dirs) {
            this.sourceDirs = List.of(dirs);
            return this;
        }

        /** Linhas de contexto antes e depois da linha do frame no trecho de fonte. */
        public Builder sourceContextLines(int lines) {
            this.sourceContextLines = lines;
            return this;
        }

        public WhyJvm build() {
            IncidentStore incidentStore = (store != null) ? store : new InMemoryIncidentStore();

            CodeContextResolver codeContext = codeAware
                    ? new CodeContextResolver(appBasePackages,
                            new SourceResolver(sourceDirs, null, sourceContextLines))
                    : null;
            EvidenceCapture capture = new JfrEvidenceCapture(incidentDir, incidentStore, codeContext);

            McpToolRegistry registry = new McpToolRegistry()
                    .register(new TriageTool(incidentStore))
                    .register(new GetExceptionDetailsTool(incidentStore))
                    .register(new GetThreadActivityTool(incidentStore))
                    .register(new GetGcActivityTool(incidentStore))
                    .register(new GetAllocationHotspotsTool(incidentStore))
                    .register(new GetLockContentionTool(incidentStore));
            // TODO Fase 3: get_slow_traces depende de capturar a arvore de spans
            //              do trace (filhos), nao so o span que disparou.

            AgentLoop agent = new AgentLoop(provider, registry, maxToolCalls);
            TriggerService triggerService = new TriggerService(capture, agent, sink, forwarder);
            IncidentDeduplicator dedup = new IncidentDeduplicator(cooldown);
            LatencyBaseline baseline = new LatencyBaseline(slowFactor);

            return new WhyJvm(new IncidentTriggerProcessor(triggerService, dedup, baseline));
        }
    }
}

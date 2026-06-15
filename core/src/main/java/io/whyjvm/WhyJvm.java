package io.whyjvm;

import io.opentelemetry.sdk.trace.SpanProcessor;
import io.whyjvm.agent.AgentLoop;
import io.whyjvm.agent.LlmProvider;
import io.whyjvm.agent.StubLlmProvider;
import io.whyjvm.capture.EvidenceCapture;
import io.whyjvm.capture.InMemoryIncidentStore;
import io.whyjvm.capture.IncidentStore;
import io.whyjvm.capture.JfrEvidenceCapture;
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

        public WhyJvm build() {
            IncidentStore incidentStore = (store != null) ? store : new InMemoryIncidentStore();

            EvidenceCapture capture = new JfrEvidenceCapture(incidentDir, incidentStore);

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
            TriggerService triggerService = new TriggerService(capture, agent, sink);
            IncidentDeduplicator dedup = new IncidentDeduplicator(cooldown);
            LatencyBaseline baseline = new LatencyBaseline(slowFactor);

            return new WhyJvm(new IncidentTriggerProcessor(triggerService, dedup, baseline));
        }
    }
}

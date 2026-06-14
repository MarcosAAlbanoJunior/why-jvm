package io.whyjvm.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.mcp.McpToolRegistry;
import io.whyjvm.mcp.ToolResult;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * O loop do agente: o unico componente que gasta token, e so roda por incidente.
 *
 * <p>Investiga -> hipotese -> drill, ate convergir para um laudo. Roda
 * desassistido numa VPS, entao tem guarda-corpos de custo: um teto de chamadas
 * de tool por incidente, para uma alucinacao nao virar um loop infinito de
 * tokens.
 */
public final class AgentLoop {

    private static final Logger LOG = Logger.getLogger(AgentLoop.class.getName());

    private static final String SYSTEM_PROMPT = """
            Voce e um analista de causa raiz de JVM. Um incidente disparou.
            Investigue usando as ferramentas disponiveis. Comece sempre por triage
            quando existir. Faca drill-down apenas na dimensao que a triagem apontar
            como suspeita. Nao chame ferramentas de dimensoes irrelevantes.
            Ao concluir, produza um laudo JSON com os campos: endpoint, tipo,
            causa_raiz, evidencia (lista), confianca (alta/media/baixa) e
            correcao_sugerida.
            """;

    private final LlmProvider provider;
    private final McpToolRegistry registry;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int maxToolCalls;

    public AgentLoop(LlmProvider provider, McpToolRegistry registry) {
        this(provider, registry, 8);
    }

    public AgentLoop(LlmProvider provider, McpToolRegistry registry, int maxToolCalls) {
        this.provider = provider;
        this.registry = registry;
        this.maxToolCalls = maxToolCalls;
    }

    public Laudo investigate(IncidentRecord incident) {
        List<Message> context = new ArrayList<>();
        context.add(Message.system(SYSTEM_PROMPT));
        context.add(Message.user(initialContext(incident)));

        int toolCallsUsed = 0;
        while (true) {
            LlmResponse response = provider.generate(context, registry.all());

            if (!response.wantsTools()) {
                return parseLaudo(response.finalText(), incident);
            }

            if (toolCallsUsed >= maxToolCalls) {
                LOG.warning("Teto de tool calls atingido (" + maxToolCalls + ") no incidente "
                        + incident.incidentId() + "; encerrando com laudo de baixa confianca.");
                return turnLimitLaudo(incident);
            }

            for (ToolCall call : response.toolCalls()) {
                ToolResult result = registry.call(call.name(), call.arguments());
                context.add(Message.assistant("[chamou " + call.name() + "]"));
                context.add(Message.toolResult(call.id(), result.content()));
                toolCallsUsed++;
            }
        }
    }

    private static String initialContext(IncidentRecord i) {
        return """
                incidentId=%s
                Tipo: %s
                Endpoint: %s
                Latencia: %dms
                """.formatted(i.incidentId(), i.type(), i.endpoint(), i.durationMs());
    }

    private Laudo parseLaudo(String json, IncidentRecord incident) {
        try {
            JsonNode n = mapper.readTree(json);
            List<String> evidencia = new ArrayList<>();
            if (n.has("evidencia") && n.get("evidencia").isArray()) {
                n.get("evidencia").forEach(e -> evidencia.add(e.asText()));
            }
            return new Laudo(
                    text(n, "endpoint", incident.endpoint()),
                    text(n, "tipo", incident.type().name()),
                    text(n, "causa_raiz", "indeterminada"),
                    evidencia,
                    text(n, "confianca", "baixa"),
                    text(n, "correcao_sugerida", "")
            );
        } catch (Exception e) {
            LOG.warning("Resposta do modelo nao era JSON valido; embrulhando como texto livre.");
            return new Laudo(incident.endpoint(), incident.type().name(), json,
                    List.of(), "baixa", "");
        }
    }

    private static String text(JsonNode n, String field, String fallback) {
        return n.hasNonNull(field) ? n.get(field).asText() : fallback;
    }

    private static Laudo turnLimitLaudo(IncidentRecord i) {
        return new Laudo(i.endpoint(), i.type().name(),
                "Investigacao interrompida pelo teto de turnos.",
                List.of("Limite de chamadas de tool atingido antes de convergir."),
                "baixa", "Revisar manualmente ou aumentar o teto de turnos.");
    }
}

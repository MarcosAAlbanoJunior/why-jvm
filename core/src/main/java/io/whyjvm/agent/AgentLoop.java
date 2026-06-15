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
            Numeros pequenos sao ruido de fundo: toda JVM sempre tem alguma alocacao
            e algum GC. So atribua a causa a uma dimensao se a magnitude for
            claramente significativa frente a latencia do incidente. Se a latencia e
            alta mas nenhuma dimensao JVM tem sinal relevante (sem exception, GC
            pequeno, alocacao baixa, sem lock), a causa provavel e EXTERNA a JVM —
            espera de I/O, query de banco ou chamada downstream; diga isso e nao
            culpe uma alocacao trivial. Calibre a confianca pela forca da evidencia:
            alta so com evidencia forte e consistente.
            Em incidentes lentos, comece o drill por get_thread_activity: ele diz se
            a thread do request esperou (sleep/I/O/lock = causa externa) ou trabalhou
            (CPU = investigar algoritmo/alocacao). So depois olhe GC/alocacao.
            Ao concluir, produza um laudo JSON com os campos: endpoint, tipo,
            causa_raiz, evidencia (lista), confianca (alta/media/baixa) e
            correcao_sugerida. Responda APENAS com o JSON cru, sem cercas de
            markdown e sem texto antes ou depois.
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

            // Registra o turno do modelo (pedido de tools) de forma estruturada,
            // depois cada resultado referenciando a chamada que respondeu.
            context.add(Message.assistantToolCalls(response.toolCalls()));
            for (ToolCall call : response.toolCalls()) {
                ToolResult result = registry.call(call.name(), call.arguments());
                context.add(Message.toolResult(call, result.content()));
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
            JsonNode n = mapper.readTree(extractJson(json));
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

    /**
     * Extrai o objeto JSON de uma resposta que pode vir embrulhada — modelos
     * costumam cercar o JSON em ```json ... ``` ou colocar texto em volta. Pega do
     * primeiro {@code &#123;} ao ultimo {@code &#125;}; se nao houver, devolve o texto cru.
     */
    static String extractJson(String text) {
        if (text == null) {
            return "";
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return (start >= 0 && end > start) ? text.substring(start, end + 1) : text;
    }

    private static Laudo turnLimitLaudo(IncidentRecord i) {
        return new Laudo(i.endpoint(), i.type().name(),
                "Investigacao interrompida pelo teto de turnos.",
                List.of("Limite de chamadas de tool atingido antes de convergir."),
                "baixa", "Revisar manualmente ou aumentar o teto de turnos.");
    }
}

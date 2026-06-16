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
            Para incidentes SLOW, get_thread_activity e OBRIGATORIO e vem ANTES de
            qualquer dimensao. Ele e a UNICA tool que filtra pela thread do request;
            get_gc_activity e get_allocation_hotspots somam a JVM INTEIRA (todas as
            threads) e frequentemente mostram ruido de fundo — alocacao de JIT/
            compilacao e de instrumentacao (ex.: ByteVector/ASM), GC disparado por
            outras requests. Se a thread do request passou o tempo ESPERANDO (sleep,
            I/O, lock, park), a causa e EXTERNA (banco, downstream, I/O) e voce NAO
            deve culpar GC/alocacao mesmo que os numeros JVM-wide sejam altos — eles
            sao de outras threads; diga que e espera externa e que nenhuma dimensao
            JVM e culpada. So investigue alocacao/GC se a thread do request esteve
            majoritariamente em CPU. Calibre a confianca pela fracao da latencia
            explicada E pela atribuicao a thread do request: nao diga 'alta' se a
            maior parte da latencia ficou sem explicacao na thread do request, nem
            com base so em numeros JVM-wide.
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

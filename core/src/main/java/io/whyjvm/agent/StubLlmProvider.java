package io.whyjvm.agent;

import io.whyjvm.mcp.Tool;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider deterministico, sem key, para fechar o circuito sem LLM e para testes.
 * Imita o caminho minimo de um agente real: {@code triage} primeiro,
 * depois drill-down em {@code get_exception_details}, e por fim um laudo JSON.
 *
 * <p>Adapta-se ao catalogo: so chama uma tool se ela estiver registrada, entao
 * funciona tanto com a triagem quanto sem (degrada para so o get_exception_details).
 *
 * <p>Troque por um provider real (Claude/Gemini) implementando {@link LlmProvider}.
 */
public final class StubLlmProvider implements LlmProvider {

    private static final Pattern INCIDENT_ID = Pattern.compile("incidentId=(\\S+)");

    @Override
    public String name() {
        return "stub";
    }

    @Override
    public LlmResponse generate(List<Message> context, List<Tool> tools) {
        String incidentId = extractIncidentId(context);

        // 1) Sempre comeca por triage, se disponivel.
        if (available(tools, "triage") && !alreadyCalled(context, "triage")) {
            return LlmResponse.callTools(List.of(
                    new ToolCall("call-triage", "triage", Map.of("incidentId", incidentId))));
        }

        // 2) Drill-down dirigido: segue o "Proximo passo sugerido" da triagem.
        //    Sem triagem, cai no get_exception_details.
        String drill = suggestedNextTool(context, tools);
        if (drill == null && available(tools, "get_exception_details")) {
            drill = "get_exception_details";
        }
        if (drill != null && !alreadyCalled(context, drill)) {
            return LlmResponse.callTools(List.of(
                    new ToolCall("call-drill", drill, Map.of("incidentId", incidentId))));
        }

        // 3) Evidencia suficiente: monta o laudo a partir do ultimo agregado lido.
        String evidence = context.stream()
                .filter(m -> m.role() == Message.Role.TOOL)
                .map(Message::content)
                .reduce((a, b) -> b)
                .orElse("");
        String firstLine = evidence.lines().filter(l -> !l.isBlank()).findFirst().orElse(evidence);

        String laudo = """
                {
                  "causa_raiz": "Diagnostico requer um provider LLM real; o stub apenas coletou a evidencia abaixo (sem analise).",
                  "evidencia": ["%s"],
                  "confianca": "baixa",
                  "correcao_sugerida": "Substituir o StubLlmProvider por um provider LLM real para analise."
                }
                """.formatted(firstLine.replace("\"", "'"));
        return LlmResponse.finalAnswer(laudo);
    }

    private static boolean available(List<Tool> tools, String name) {
        return tools.stream().anyMatch(t -> name.equals(t.name()));
    }

    /** Le o "Proximo passo sugerido: X" da triagem; devolve X se for tool registrada. */
    private static String suggestedNextTool(List<Message> context, List<Tool> tools) {
        String marker = "Proximo passo sugerido:";
        for (Message m : context) {
            if (m.role() != Message.Role.TOOL || m.content() == null) {
                continue;
            }
            for (String line : m.content().split("\\R")) {
                String s = line.strip();
                if (s.startsWith(marker)) {
                    String token = s.substring(marker.length()).strip();
                    int space = token.indexOf(' ');
                    if (space >= 0) {
                        token = token.substring(0, space);
                    }
                    if (available(tools, token)) {
                        return token;
                    }
                }
            }
        }
        return null;
    }

    /** Olha os turnos de assistente que pediram tools para ver se {@code toolName} ja rodou. */
    private static boolean alreadyCalled(List<Message> context, String toolName) {
        return context.stream()
                .filter(m -> m.role() == Message.Role.ASSISTANT)
                .flatMap(m -> m.toolCalls().stream())
                .anyMatch(c -> toolName.equals(c.name()));
    }

    private static String extractIncidentId(List<Message> context) {
        for (Message m : context) {
            Matcher matcher = INCIDENT_ID.matcher(m.content() == null ? "" : m.content());
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        return "unknown";
    }
}

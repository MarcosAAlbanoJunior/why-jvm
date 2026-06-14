package io.whyjvm.agent;

import io.whyjvm.mcp.Tool;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provider deterministico, sem key, para fechar o circuito da Fase 0 e para
 * testes. Imita o comportamento minimo de um agente: na primeira rodada chama
 * {@code get_exception_details}; depois do resultado, devolve um laudo JSON.
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
        boolean alreadyInvestigated = context.stream()
                .anyMatch(m -> m.role() == Message.Role.TOOL);

        if (!alreadyInvestigated) {
            String incidentId = extractIncidentId(context);
            return LlmResponse.callTools(List.of(
                    new ToolCall("call-1", "get_exception_details",
                            Map.of("incidentId", incidentId))
            ));
        }

        String evidence = context.stream()
                .filter(m -> m.role() == Message.Role.TOOL)
                .map(Message::content)
                .reduce((a, b) -> b)
                .orElse("");
        String firstLine = evidence.lines().filter(l -> !l.isBlank()).findFirst().orElse(evidence);

        String laudo = """
                {
                  "causa_raiz": "Exception nao tratada no endpoint (stub provider, sem analise real).",
                  "evidencia": ["%s"],
                  "confianca": "baixa",
                  "correcao_sugerida": "Substituir o StubLlmProvider por um provider LLM real para analise."
                }
                """.formatted(firstLine.replace("\"", "'"));
        return LlmResponse.finalAnswer(laudo);
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

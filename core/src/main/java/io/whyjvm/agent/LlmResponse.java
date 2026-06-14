package io.whyjvm.agent;

import java.util.List;

/**
 * Resposta de um turno do modelo: ou ele pede tools, ou entrega o laudo final.
 */
public record LlmResponse(List<ToolCall> toolCalls, String finalText) {

    public boolean wantsTools() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public static LlmResponse callTools(List<ToolCall> calls) {
        return new LlmResponse(calls, null);
    }

    public static LlmResponse finalAnswer(String text) {
        return new LlmResponse(List.of(), text);
    }
}

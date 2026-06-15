package io.whyjvm.agent;

import java.util.List;

/**
 * Uma mensagem no contexto do agente. Formato neutro de provider: cada
 * implementacao de {@link LlmProvider} traduz para o seu proprio schema de
 * function calling.
 *
 * <p>Um turno de assistente pode ser <b>texto</b> (laudo final) ou um pedido de
 * <b>tools</b> ({@code toolCalls} preenchido) — providers reais (Gemini, Claude)
 * exigem que o pedido de tool apareca no historico de forma estruturada, com
 * nome e argumentos, e que a resposta da tool referencie a chamada que respondeu.
 */
public record Message(Role role, String content, List<ToolCall> toolCalls, ToolCall toolResultFor) {

    public enum Role {SYSTEM, USER, ASSISTANT, TOOL}

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, List.of(), null);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, List.of(), null);
    }

    /** Turno de assistente em texto (ex.: o laudo final). */
    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, List.of(), null);
    }

    /** Turno de assistente que pede tools (function calling). */
    public static Message assistantToolCalls(List<ToolCall> toolCalls) {
        return new Message(Role.ASSISTANT, null, List.copyOf(toolCalls), null);
    }

    /** Resultado de uma tool, referenciando a chamada {@code call} que respondeu. */
    public static Message toolResult(ToolCall call, String content) {
        return new Message(Role.TOOL, content, List.of(), call);
    }
}

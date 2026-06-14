package io.whyjvm.agent;

/**
 * Uma mensagem no contexto do agente. Formato neutro de provider: cada
 * implementacao de {@link LlmProvider} traduz para o seu proprio schema de
 * function calling.
 */
public record Message(Role role, String content, String toolCallId) {

    public enum Role {SYSTEM, USER, ASSISTANT, TOOL}

    public static Message system(String content) {
        return new Message(Role.SYSTEM, content, null);
    }

    public static Message user(String content) {
        return new Message(Role.USER, content, null);
    }

    public static Message assistant(String content) {
        return new Message(Role.ASSISTANT, content, null);
    }

    public static Message toolResult(String toolCallId, String content) {
        return new Message(Role.TOOL, content, toolCallId);
    }
}

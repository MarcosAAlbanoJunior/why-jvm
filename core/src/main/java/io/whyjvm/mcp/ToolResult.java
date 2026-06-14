package io.whyjvm.mcp;

/**
 * Resultado de uma tool: texto/JSON agregado que volta para o contexto do
 * agente, mais um flag de erro para o loop saber que a chamada falhou.
 */
public record ToolResult(String content, boolean isError) {

    public static ToolResult ok(String content) {
        return new ToolResult(content, false);
    }

    public static ToolResult error(String message) {
        return new ToolResult(message, true);
    }
}

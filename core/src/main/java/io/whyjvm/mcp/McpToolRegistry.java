package io.whyjvm.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalogo das tools disponiveis ao agente. O agente recebe a lista para o
 * function calling e chama por nome.
 */
public final class McpToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public McpToolRegistry register(Tool tool) {
        tools.put(tool.name(), tool);
        return this;
    }

    public List<Tool> all() {
        return List.copyOf(tools.values());
    }

    public ToolResult call(String name, Map<String, Object> arguments) {
        Tool tool = tools.get(name);
        if (tool == null) {
            return ToolResult.error("Tool desconhecida: " + name);
        }
        try {
            return tool.execute(arguments);
        } catch (Exception e) {
            return ToolResult.error("Falha ao executar " + name + ": " + e.getMessage());
        }
    }
}

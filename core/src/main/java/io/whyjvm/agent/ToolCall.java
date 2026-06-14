package io.whyjvm.agent;

import java.util.Map;

/** Um pedido do modelo para executar uma tool, com argumentos ja desserializados. */
public record ToolCall(String id, String name, Map<String, Object> arguments) {
}

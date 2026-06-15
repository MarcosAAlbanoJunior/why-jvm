package io.whyjvm.llm;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.whyjvm.agent.LlmProvider;
import io.whyjvm.agent.LlmResponse;
import io.whyjvm.agent.Message;
import io.whyjvm.agent.ToolCall;
import io.whyjvm.mcp.Tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Provider real sobre o {@code ChatModel} do LangChain4j. Usamos só a chamada de
 * chat+tools dele (o pedaço que traduz o formato de function calling de cada
 * provider); o cerebro — loop, triagem, dois portoes — continua sendo o nosso
 * {@code AgentLoop}.
 *
 * <p>Trocar de provider (Gemini, Claude, OpenAI, Ollama) e trocar o
 * {@link ChatModel} injetado; este adapter nao muda. Ver {@link LlmProviders}.
 */
public final class LangChain4jProvider implements LlmProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final ChatModel model;

    public LangChain4jProvider(String name, ChatModel model) {
        this.name = name;
        this.model = model;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public LlmResponse generate(List<Message> context, List<Tool> tools) {
        ChatRequest request = ChatRequest.builder()
                .messages(toChatMessages(context))
                .toolSpecifications(toToolSpecifications(tools))
                .build();

        ChatResponse response = model.chat(request);
        AiMessage ai = response.aiMessage();

        if (ai.hasToolExecutionRequests()) {
            List<ToolCall> calls = new ArrayList<>();
            int i = 0;
            for (ToolExecutionRequest req : ai.toolExecutionRequests()) {
                // Gemini nao manda id; sintetizamos um estavel para o pareamento interno.
                String id = (req.id() != null && !req.id().isBlank()) ? req.id() : req.name() + "-" + (i++);
                calls.add(new ToolCall(id, req.name(), argsFromJson(req.arguments())));
            }
            return LlmResponse.callTools(calls);
        }
        return LlmResponse.finalAnswer(ai.text());
    }

    // ---- mapeamento -> LangChain4j (pacote: visivel para teste) ----

    static List<ChatMessage> toChatMessages(List<Message> context) {
        List<ChatMessage> out = new ArrayList<>();
        for (Message m : context) {
            switch (m.role()) {
                case SYSTEM -> out.add(SystemMessage.from(m.content()));
                case USER -> out.add(UserMessage.from(m.content()));
                case ASSISTANT -> {
                    if (m.toolCalls().isEmpty()) {
                        out.add(AiMessage.from(m.content()));
                    } else {
                        out.add(AiMessage.from(m.toolCalls().stream()
                                .map(LangChain4jProvider::toToolExecutionRequest).toList()));
                    }
                }
                case TOOL -> out.add(ToolExecutionResultMessage.from(
                        toToolExecutionRequest(m.toolResultFor()), m.content()));
            }
        }
        return out;
    }

    static ToolExecutionRequest toToolExecutionRequest(ToolCall call) {
        return ToolExecutionRequest.builder()
                .id(call.id())
                .name(call.name())
                .arguments(argsToJson(call.arguments()))
                .build();
    }

    static List<ToolSpecification> toToolSpecifications(List<Tool> tools) {
        List<ToolSpecification> specs = new ArrayList<>();
        for (Tool tool : tools) {
            specs.add(ToolSpecification.builder()
                    .name(tool.name())
                    .description(tool.description())
                    .parameters(toJsonObjectSchema(tool.inputSchema()))
                    .build());
        }
        return specs;
    }

    static JsonObjectSchema toJsonObjectSchema(Map<String, Object> schema) {
        JsonObjectSchema.Builder builder = JsonObjectSchema.builder();
        if (schema.get("properties") instanceof Map<?, ?> properties) {
            for (Map.Entry<?, ?> entry : properties.entrySet()) {
                String propName = String.valueOf(entry.getKey());
                String description = "";
                if (entry.getValue() instanceof Map<?, ?> def && def.get("description") != null) {
                    description = String.valueOf(def.get("description"));
                }
                // Nossas tools usam apenas propriedades string (incidentId, endpoint).
                builder.addStringProperty(propName, description);
            }
        }
        if (schema.get("required") instanceof String[] required) {
            builder.required(required);
        }
        return builder.build();
    }

    private static String argsToJson(Map<String, Object> args) {
        try {
            return MAPPER.writeValueAsString(args == null ? Map.of() : args);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static Map<String, Object> argsFromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }
}

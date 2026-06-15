package io.whyjvm.llm;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import io.whyjvm.agent.Message;
import io.whyjvm.agent.ToolCall;
import io.whyjvm.mcp.Tool;
import io.whyjvm.mcp.ToolResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa o mapeamento nosso -> LangChain4j isolado da rede (nao chama o modelo).
 */
class LangChain4jProviderTest {

    private static Tool fakeTool() {
        return new Tool() {
            @Override
            public String name() {
                return "triage";
            }

            @Override
            public String description() {
                return "triagem do incidente";
            }

            @Override
            public Map<String, Object> inputSchema() {
                return Map.of(
                        "type", "object",
                        "properties", Map.of("incidentId", Map.of("type", "string", "description", "id")),
                        "required", new String[]{"incidentId"});
            }

            @Override
            public ToolResult execute(Map<String, Object> arguments) {
                return ToolResult.ok("x");
            }
        };
    }

    @Test
    void toolSpecificationCarriesNameAndRequiredParam() {
        List<ToolSpecification> specs = LangChain4jProvider.toToolSpecifications(List.of(fakeTool()));

        assertEquals(1, specs.size());
        ToolSpecification spec = specs.get(0);
        assertEquals("triage", spec.name());
        assertEquals("triagem do incidente", spec.description());
        assertNotNull(spec.parameters());
        assertTrue(spec.parameters().required().contains("incidentId"), "incidentId deve ser obrigatorio");
    }

    @Test
    void mapsContextRolesToChatMessageTypes() {
        ToolCall call = new ToolCall("c1", "triage", Map.of("incidentId", "inc-1"));
        List<Message> context = List.of(
                Message.system("voce e um analista"),
                Message.user("incidente inc-1"),
                Message.assistantToolCalls(List.of(call)),
                Message.toolResult(call, "saida da triagem"),
                Message.assistant("laudo final"));

        List<ChatMessage> out = LangChain4jProvider.toChatMessages(context);

        assertEquals(5, out.size());
        assertInstanceOf(SystemMessage.class, out.get(0));
        assertInstanceOf(UserMessage.class, out.get(1));
        assertInstanceOf(AiMessage.class, out.get(2));
        assertTrue(((AiMessage) out.get(2)).hasToolExecutionRequests(), "turno de tool calls");
        assertInstanceOf(ToolExecutionResultMessage.class, out.get(3));
        assertInstanceOf(AiMessage.class, out.get(4));
        assertFalse(((AiMessage) out.get(4)).hasToolExecutionRequests(), "laudo final e texto");
    }
}

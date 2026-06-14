package io.whyjvm.mcp.tools;

import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.IncidentStore;
import io.whyjvm.mcp.Tool;
import io.whyjvm.mcp.ToolResult;

import java.util.Map;

/**
 * Fase 0: a unica tool do circuito fechado. Devolve o stack trace da exception,
 * a mensagem e o span de origem do incidente.
 */
public final class GetExceptionDetailsTool implements Tool {

    private final IncidentStore store;

    public GetExceptionDetailsTool(IncidentStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "get_exception_details";
    }

    @Override
    public String description() {
        return "Stack trace da exception, mensagem e span de origem do incidente.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "incidentId", Map.of(
                                "type", "string",
                                "description", "Id do incidente a investigar."
                        )
                ),
                "required", new String[]{"incidentId"}
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> arguments) {
        String incidentId = String.valueOf(arguments.get("incidentId"));
        IncidentRecord record = store.find(incidentId).orElse(null);
        if (record == null) {
            return ToolResult.error("Incidente nao encontrado: " + incidentId);
        }
        if (record.exceptionType() == null) {
            return ToolResult.ok("O incidente " + incidentId + " nao tem exception anexada (tipo: "
                    + record.type() + ").");
        }

        String body = """
                Endpoint: %s
                Tipo: %s
                Exception: %s
                Mensagem: %s
                Stack trace:
                %s
                """.formatted(
                record.endpoint(),
                record.type(),
                record.exceptionType(),
                record.exceptionMessage(),
                record.exceptionStackTrace()
        );
        return ToolResult.ok(body);
    }
}

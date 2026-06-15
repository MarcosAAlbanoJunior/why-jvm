package io.whyjvm.mcp.tools;

import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.IncidentStore;
import io.whyjvm.mcp.Tool;
import io.whyjvm.mcp.ToolResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/**
 * Base das tools que leem o snapshot JFR de um incidente. Cuida do boilerplate
 * comum — carregar o registro, checar que existe snapshot, tratar erro de I/O —
 * deixando cada tool concreta com apenas a sua agregacao de dimensao.
 */
abstract class JfrDimensionTool implements Tool {

    protected final IncidentStore store;

    protected JfrDimensionTool(IncidentStore store) {
        this.store = store;
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
        if (record.jfrSnapshot() == null) {
            return ToolResult.ok("Incidente " + incidentId + " nao tem snapshot JFR "
                    + "(a captura nao gerou evidencia desta janela).");
        }
        try {
            return ToolResult.ok(aggregate(record, record.jfrSnapshot()));
        } catch (IOException e) {
            return ToolResult.error("Falha ao ler o snapshot JFR de " + incidentId + ": " + e.getMessage());
        }
    }

    /** Le o snapshot e devolve o agregado da dimensao, pronto para o contexto. */
    protected abstract String aggregate(IncidentRecord record, Path jfr) throws IOException;
}

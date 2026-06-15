package io.whyjvm.mcp.tools;

import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.IncidentStore;
import io.whyjvm.mcp.Tool;
import io.whyjvm.mcp.ToolResult;

import java.util.Map;

/**
 * Base das tools de dimensao. Cuida do boilerplate comum — carregar o registro,
 * tratar incidente inexistente — deixando cada tool concreta apenas com a
 * renderizacao do seu agregado.
 *
 * <p>As tools <b>nao leem mais JFR</b>: o {@link io.whyjvm.capture.EvidenceExtractor}
 * ja extraiu os agregados na captura e os congelou no {@link IncidentRecord}. Aqui
 * so se transforma o agregado estruturado em texto para o contexto do agente.
 */
abstract class DimensionTool implements Tool {

    protected final IncidentStore store;

    protected DimensionTool(IncidentStore store) {
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
        return ToolResult.ok(render(record));
    }

    /** Renderiza o agregado da dimensao a partir do registro ja extraido. */
    protected abstract String render(IncidentRecord record);
}

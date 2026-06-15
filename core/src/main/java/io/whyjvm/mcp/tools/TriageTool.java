package io.whyjvm.mcp.tools;

import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.IncidentStore;
import io.whyjvm.mcp.Tool;
import io.whyjvm.mcp.ToolResult;
import io.whyjvm.trigger.IncidentType;

import java.util.Map;

/**
 * Fase 2: a triagem deterministica. E o coracao da economia e <b>sempre</b> a
 * primeira chamada do agente.
 *
 * <p>Roda uma correlacao barata sobre o pacote de evidencia e entrega ao agente
 * uma visao geral + uma <b>hipotese inicial</b> com a dimensao suspeita. Assim o
 * agente ja comeca apontado na direcao certa e gasta menos turnos — nao faz
 * drill-down em dimensoes irrelevantes.
 *
 * <p>Nao gasta token proprio e nao chama LLM: e if/else e numeros. A correlacao
 * fica aqui; a narrativa e a correcao ficam com o agente.
 *
 * <p>TODO Fase 3: quando as tools de GC/lock/alocacao lerem o snapshot JFR, a
 * triagem passa a correlacionar latencia x pausa de GC x contencao de lock e a
 * marcar essas dimensoes como anomalas (hoje ficam "nao avaliado").
 */
public final class TriageTool implements Tool {

    private final IncidentStore store;

    public TriageTool(IncidentStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "triage";
    }

    @Override
    public String description() {
        return "Visao geral do incidente e hipotese inicial: tipo, exception, latencia e a "
                + "dimensao suspeita. Chame PRIMEIRO, antes de qualquer drill-down.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "incidentId", Map.of(
                                "type", "string",
                                "description", "Id do incidente a triar."
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
        return ToolResult.ok(report(record));
    }

    /** Monta o agregado da triagem. Pacote: visivel para teste. */
    static String report(IncidentRecord r) {
        Hypothesis h = hypothesize(r);
        boolean hasException = r.exceptionType() != null;
        String exceptionLine = !hasException
                ? "(nenhuma)"
                : r.exceptionType()
                + (r.exceptionMessage() != null ? " — \"" + firstLine(r.exceptionMessage()) + "\"" : "");

        return """
                TRIAGEM do incidente %s
                Tipo: %s
                Endpoint: %s
                Latencia: %dms
                Exception: %s

                Dimensoes:
                - exception:  %s
                - gc:         nao avaliado (Fase 3)
                - lock:       nao avaliado (Fase 3)
                - alocacao:   nao avaliado (Fase 3)
                - downstream: nao avaliado (Fase 3)

                Hipotese inicial: %s
                Dimensao suspeita: %s
                Proximo passo sugerido: %s
                """.formatted(
                r.incidentId(), r.type(), r.endpoint(), r.durationMs(), exceptionLine,
                hasException ? "ANOMALA (exception anexada ao span)" : "sem exception",
                h.hypothesis(), h.dimension(), h.nextStep());
    }

    private static Hypothesis hypothesize(IncidentRecord r) {
        if (r.type() == IncidentType.ERROR && r.exceptionType() != null) {
            return new Hypothesis(
                    "erro de aplicacao — uma exception nao tratada propagou ate a borda do request.",
                    "exception",
                    "get_exception_details");
        }
        if (r.type() == IncidentType.ERROR) {
            return new Hypothesis(
                    "request terminou com status de erro, mas sem exception anexada ao span.",
                    "exception (sem stack)",
                    "get_exception_details");
        }
        // SLOW: deteccao por baseline e correlacao JFR (GC/lock/downstream) chegam na Fase 3.
        return new Hypothesis(
                "latencia acima do baseline; a dimensao sera confirmada quando as tools JFR existirem.",
                "indefinida (aguardando Fase 3)",
                "tools de GC/lock/downstream (Fase 3)");
    }

    private static String firstLine(String s) {
        return s.lines().findFirst().orElse(s);
    }

    private record Hypothesis(String hypothesis, String dimension, String nextStep) {
    }
}

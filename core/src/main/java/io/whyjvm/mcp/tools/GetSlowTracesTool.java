package io.whyjvm.mcp.tools;

import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.IncidentStore;
import io.whyjvm.capture.SlowTrace;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A arvore do trace do request lento (Tier 3): <b>qual span/metodo dominou a
 * latencia</b> e se ha <b>N+1</b> (muitas chamadas identicas). Diferente das
 * dimensoes JVM-wide, mostra onde o TEMPO foi gasto — banco/downstream/CPU por
 * span — usando o self time (tempo proprio, descontados os filhos).
 *
 * <p>Le o agregado ja montado ({@code dimensions.slowTraces}); nao toca no JFR.
 * Depende de a app gerar sub-spans (cliente HTTP/JDBC instrumentado): sem eles,
 * a dimensao vem vazia e a tool diz isso — honesto.
 */
public final class GetSlowTracesTool extends DimensionTool {

    public GetSlowTracesTool(IncidentStore store) {
        super(store);
    }

    @Override
    public String name() {
        return "get_slow_traces";
    }

    @Override
    public String description() {
        return "Spans mais lentos do trace: qual span/metodo dominou a latencia (self time) e "
                + "deteccao de N+1 (chamadas identicas repetidas). Use quando a causa nao e JVM-wide.";
    }

    @Override
    protected String render(IncidentRecord record) {
        List<SlowTrace> traces = record.dimensions().slowTraces();
        if (traces == null || traces.isEmpty()) {
            return "Sem arvore de trace para este incidente — a app nao gerou sub-spans na janela "
                    + "(cliente HTTP/JDBC instrumentado?). Nada a atribuir por span.";
        }
        long incidentMs = Math.max(1, record.durationMs());
        String rows = traces.stream()
                .map(t -> "  - " + t.span() + ": self " + t.selfMs() + "ms, total " + t.totalMs()
                        + "ms (~" + (t.totalMs() * 100 / incidentMs) + "% da latencia)")
                .collect(Collectors.joining("\n"));

        boolean hasNPlusOne = traces.stream().anyMatch(t -> t.span().startsWith("N+1"));
        String conclusion = hasNPlusOne
                ? "N+1 detectado: chamadas identicas repetidas dominam o tempo — agrupe/elimine as repeticoes "
                + "(batch, join, cache) em vez de otimizar uma chamada isolada."
                : "O span no topo concentra o self time; investigue-o (consulta/downstream/algoritmo) "
                + "antes de qualquer dimensao JVM-wide.";

        return """
                Spans mais lentos do trace (latencia do incidente: %dms):
                %s

                %s
                """.formatted(incidentMs, rows, conclusion);
    }
}

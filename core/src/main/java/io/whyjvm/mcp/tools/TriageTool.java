package io.whyjvm.mcp.tools;

import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.IncidentStore;
import io.whyjvm.mcp.Tool;
import io.whyjvm.mcp.ToolResult;
import io.whyjvm.mcp.tools.JfrCorrelation.Signals;
import io.whyjvm.trigger.IncidentType;

import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Fase 2/3: a triagem deterministica. E o coracao da economia e <b>sempre</b> a
 * primeira chamada do agente.
 *
 * <p>Roda uma correlacao barata sobre o pacote de evidencia e entrega ao agente
 * uma <b>hipotese inicial</b> com a dimensao suspeita. Assim o agente ja comeca
 * apontado e gasta menos turnos — nao faz drill-down em dimensoes irrelevantes.
 *
 * <p>Fase 3: quando ha snapshot JFR, le os numeros headline de cada dimensao
 * (maior pausa de GC, espera de lock, bytes alocados) numa unica passada via
 * {@link JfrCorrelation} e, para incidentes SLOW, escolhe a dimensao que mais
 * pesa na latencia. Nao chama LLM nem gasta token: e if/else e numeros.
 */
public final class TriageTool implements Tool {

    private static final Logger LOG = Logger.getLogger(TriageTool.class.getName());

    /** Fracao da latencia a partir da qual uma dimensao JFR e considerada suspeita. */
    private static final double SHARE_THRESHOLD = 0.10;
    /** Acima disto, a alocacao amostrada e um sinal (pressao sobre o GC). */
    private static final long ALLOC_BYTES_THRESHOLD = 50L * 1024 * 1024;

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
        Signals sig = readSignals(r);
        Hypothesis h = hypothesize(r, sig);
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
                - gc:         %s
                - lock:       %s
                - alocacao:   %s
                - downstream: nao avaliado (requer captura do trace; get_slow_traces pendente)

                Hipotese inicial: %s
                Dimensao suspeita: %s
                Proximo passo sugerido: %s
                """.formatted(
                r.incidentId(), r.type(), r.endpoint(), r.durationMs(), exceptionLine,
                hasException ? "ANOMALA (exception anexada ao span)" : "sem exception",
                gcLine(sig), lockLine(sig), allocLine(sig),
                h.hypothesis(), h.dimension(), h.nextStep());
    }

    private static Signals readSignals(IncidentRecord r) {
        if (r.jfrSnapshot() == null) {
            return null;
        }
        try {
            return JfrCorrelation.read(r.jfrSnapshot(), r.capturedAt());
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Falha ao correlacionar JFR do incidente " + r.incidentId(), e);
            return null;
        }
    }

    private static Hypothesis hypothesize(IncidentRecord r, Signals sig) {
        if (r.type() == IncidentType.ERROR && r.exceptionType() != null) {
            return new Hypothesis(
                    "erro de aplicacao — uma exception nao tratada propagou ate a borda do request.",
                    "exception", "get_exception_details");
        }
        if (r.type() == IncidentType.ERROR) {
            return new Hypothesis(
                    "request terminou com status de erro, mas sem exception anexada ao span.",
                    "exception (sem stack)", "get_exception_details");
        }
        // SLOW: escolhe a dimensao JFR que mais pesa na latencia.
        if (sig == null) {
            return new Hypothesis(
                    "sem snapshot JFR — a captura nao gerou evidencia desta janela.",
                    "indefinida (sem JFR)", "(sem evidencia para drill-down)");
        }
        long dur = Math.max(1, r.durationMs());
        double share = dur * SHARE_THRESHOLD;
        if (sig.longestGcPauseMs() >= sig.totalLockWaitMs() && sig.longestGcPauseMs() >= share) {
            return new Hypothesis(
                    "pausa de GC de " + sig.longestGcPauseMs() + "ms na janela (~"
                            + pct(sig.longestGcPauseMs(), dur) + "% da latencia).",
                    "gc", "get_gc_activity");
        }
        if (sig.totalLockWaitMs() > sig.longestGcPauseMs() && sig.totalLockWaitMs() >= share) {
            return new Hypothesis(
                    "contencao de lock somou " + sig.totalLockWaitMs() + "ms de espera (~"
                            + pct(sig.totalLockWaitMs(), dur) + "% da latencia).",
                    "lock", "get_lock_contention");
        }
        if (sig.totalAllocBytes() > ALLOC_BYTES_THRESHOLD) {
            return new Hypothesis(
                    "alocacao alta na janela (" + (sig.totalAllocBytes() / (1024 * 1024))
                            + " MB amostrados); pode estar pressionando o GC.",
                    "alocacao", "get_allocation_hotspots");
        }
        return new Hypothesis(
                "latencia alta sem sinal forte de GC/lock/alocacao JVM-wide — confirme o que a thread do "
                        + "request fez (espera vs trabalho) antes de concluir; nao culpe alocacao/GC triviais.",
                "a confirmar (espera vs trabalho)",
                "get_thread_activity");
    }

    private static String gcLine(Signals s) {
        if (s == null) {
            return "sem snapshot JFR";
        }
        if (s.gcCount() == 0) {
            return "sem coletas na janela";
        }
        return "maior pausa %dms, %d coletas (total %dms)"
                .formatted(s.longestGcPauseMs(), s.gcCount(), s.totalGcPauseMs());
    }

    private static String lockLine(Signals s) {
        if (s == null) {
            return "sem snapshot JFR";
        }
        return s.totalLockWaitMs() == 0 ? "sem contencao relevante"
                : "espera total %dms".formatted(s.totalLockWaitMs());
    }

    private static String allocLine(Signals s) {
        if (s == null) {
            return "sem snapshot JFR";
        }
        return s.totalAllocBytes() == 0 ? "sem amostras"
                : "%d MB amostrados".formatted(s.totalAllocBytes() / (1024 * 1024));
    }

    private static long pct(long part, long whole) {
        return part * 100 / whole;
    }

    private static String firstLine(String s) {
        return s.lines().findFirst().orElse(s);
    }

    private record Hypothesis(String hypothesis, String dimension, String nextStep) {
    }
}

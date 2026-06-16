package io.whyjvm.mcp.tools;

import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.IncidentStore;
import io.whyjvm.capture.TriageSignals;
import io.whyjvm.mcp.Tool;
import io.whyjvm.mcp.ToolResult;
import io.whyjvm.trigger.IncidentType;

import java.util.Map;

/**
 * A triagem deterministica. E o coracao da economia e <b>sempre</b> a primeira
 * chamada do agente.
 *
 * <p>Entrega ao agente uma <b>hipotese inicial</b> com a dimensao suspeita, a
 * partir dos sinais headline ({@link TriageSignals}) que o
 * {@link io.whyjvm.capture.EvidenceExtractor} ja calculou na captura. Para
 * incidentes SLOW, escolhe a dimensao que mais pesa na latencia. Nao chama LLM
 * nem le JFR: e if/else e numeros sobre o agregado pronto.
 */
public final class TriageTool implements Tool {

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
        TriageSignals sig = r.triageSignals();
        Hypothesis h = hypothesize(r, sig);
        boolean hasException = r.exception() != null;
        String exceptionLine = !hasException
                ? "(nenhuma)"
                : r.exception().type()
                + (r.exception().message() != null ? " — \"" + firstLine(r.exception().message()) + "\"" : "");

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

    private static Hypothesis hypothesize(IncidentRecord r, TriageSignals sig) {
        if (r.type() == IncidentType.ERROR && r.exception() != null) {
            return new Hypothesis(
                    "erro de aplicacao — uma exception nao tratada propagou ate a borda do request.",
                    "exception", "get_exception_details");
        }
        if (r.type() == IncidentType.ERROR) {
            return new Hypothesis(
                    "request terminou com status de erro, mas sem exception anexada ao span.",
                    "exception (sem stack)", "get_exception_details");
        }
        // SLOW: as tools de GC/alocacao sao JVM-WIDE (somam TODAS as threads) e nao
        // sabem se o sinal esta na thread do request. get_thread_activity e a UNICA
        // que filtra pela thread — entao e SEMPRE o primeiro drill no SLOW. A
        // dimensao mais alta vira so um candidato a confirmar (ou descartar, se a
        // thread so esperou).
        if (sig == null) {
            return new Hypothesis(
                    "sem snapshot JFR — a captura nao gerou evidencia desta janela.",
                    "indefinida (sem JFR)", "(sem evidencia para drill-down)");
        }
        long dur = Math.max(1, r.durationMs());
        String candidate = slowCandidate(sig, dur);
        return new Hypothesis(
                "latencia alta (" + dur + "ms). Sinais JVM-wide (somam TODAS as threads, podem ser ruido "
                        + "de fundo): " + candidate + ". Confirme PRIMEIRO se a thread do request esperou "
                        + "(causa externa) ou trabalhou (JVM) antes de culpar qualquer dimensao.",
                "a confirmar via thread_activity (candidato: " + candidate + ")",
                "get_thread_activity");
    }

    /**
     * Aponta a dimensao JVM-wide mais alta como hipotese a confirmar — nao como
     * veredito (pode ser ruido de outra thread; so a thread do request distingue).
     */
    private static String slowCandidate(TriageSignals sig, long dur) {
        double share = dur * SHARE_THRESHOLD;
        if (sig.longestGcPauseMs() >= share && sig.longestGcPauseMs() >= sig.totalLockWaitMs()) {
            return "gc (maior pausa " + sig.longestGcPauseMs() + "ms, ~"
                    + pct(sig.longestGcPauseMs(), dur) + "% da latencia)";
        }
        if (sig.totalLockWaitMs() >= share) {
            return "lock (espera total " + sig.totalLockWaitMs() + "ms, ~"
                    + pct(sig.totalLockWaitMs(), dur) + "% da latencia)";
        }
        if (sig.totalAllocBytes() > ALLOC_BYTES_THRESHOLD) {
            return "alocacao (" + (sig.totalAllocBytes() / (1024 * 1024)) + " MB amostrados, JVM-wide)";
        }
        return "nenhuma dimensao JVM com sinal forte (provavel causa externa: espera/IO/downstream)";
    }

    private static String gcLine(TriageSignals s) {
        if (s == null) {
            return "sem snapshot JFR";
        }
        if (s.gcCount() == 0) {
            return "sem coletas na janela";
        }
        return "maior pausa %dms, %d coletas (total %dms)"
                .formatted(s.longestGcPauseMs(), s.gcCount(), s.totalGcPauseMs());
    }

    private static String lockLine(TriageSignals s) {
        if (s == null) {
            return "sem snapshot JFR";
        }
        return s.totalLockWaitMs() == 0 ? "sem contencao relevante"
                : "espera total %dms".formatted(s.totalLockWaitMs());
    }

    private static String allocLine(TriageSignals s) {
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

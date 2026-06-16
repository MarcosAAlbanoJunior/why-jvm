package io.whyjvm.agent;

import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.JvmContext;
import io.whyjvm.capture.ThreadActivity;
import io.whyjvm.capture.TriageSignals;

import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostico diferencial DETERMINISTICO: monta as hipoteses DESCARTADAS a partir
 * dos sinais ja medidos — nao do LLM. Cada item e ancorado num numero. Codifica a
 * distincao JVM-wide vs thread do request: alocacao/CPU so sao causa se a thread
 * do request trabalhou (uma pausa de GC, stop-the-world, afeta todas as threads —
 * essa nao se descarta por espera). Sem snapshot JFR, nao descarta nada (honesto).
 *
 * <p>Espelha {@code differential.go} do servico de analise.
 */
public final class Differential {

    private static final double SHARE = 0.10;
    private static final long ALLOC_NOISE_BYTES = 50L * 1024 * 1024;
    private static final long HEALTHY_HEAP_PCT = 85;

    private Differential() {
    }

    public static List<String> of(IncidentRecord rec) {
        TriageSignals sig = rec.triageSignals();
        if (sig == null) {
            return List.of();
        }
        long dur = Math.max(1, rec.durationMs());
        double share = dur * SHARE;
        ThreadActivity ta = rec.dimensions().threadActivity();
        boolean threadWorked = ta != null && ta.cpuSamples() > 0;
        boolean threadWaited = ta != null && (ta.sleepMs() + ta.ioMs() + ta.parkMs()) >= share;

        List<String> out = new ArrayList<>();

        // Pausa de GC: stop-the-world afeta todas as threads, entao so o tamanho conta.
        if (sig.longestGcPauseMs() < share) {
            out.add("Pausas de GC: irrelevantes (maior " + sig.longestGcPauseMs() + "ms)");
        }
        // Contencao de lock / deadlock.
        if (sig.totalLockWaitMs() < share) {
            out.add("Contencao de lock / deadlock: sem espera relevante em monitor");
        }
        // CPU (thread do request).
        if (ta != null && ta.cpuSamples() == 0) {
            out.add("CPU: a thread do request nao estava em CPU");
        }
        // Alocacao: JVM-wide. So e causa se a thread do request esteve em CPU.
        if (sig.totalAllocBytes() < ALLOC_NOISE_BYTES) {
            out.add("Pressao de alocacao: baixa (" + (sig.totalAllocBytes() / (1024 * 1024)) + " MB amostrados)");
        } else if (!threadWorked) {
            out.add("Pressao de alocacao: " + (sig.totalAllocBytes() / (1024 * 1024)) + " MB amostrados, mas "
                    + "JVM-wide e a thread do request nao estava em CPU (ruido de fundo, nao a causa)");
        }
        // Espera externa (I/O / banco / downstream): da thread do request.
        if (ta != null && !threadWaited) {
            out.add("Espera externa (I/O / banco / downstream): sem espera relevante na thread do request");
        }
        // Heap: so quando jvmContext for populado (dos MXBeans).
        JvmContext jc = rec.jvmContext();
        if (jc != null && jc.heapMaxMb() > 0) {
            long usage = jc.heapUsedMb() * 100 / jc.heapMaxMb();
            if (usage < HEALTHY_HEAP_PCT) {
                out.add("Heap: uso saudavel (" + usage + "%)");
            }
        }
        return out;
    }
}

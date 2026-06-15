package io.whyjvm.mcp.tools;

import jdk.jfr.consumer.RecordedEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Correlacao barata para a triagem: uma unica passada pelo snapshot que extrai
 * os numeros headline de cada dimensao (maior pausa de GC, espera de lock,
 * bytes alocados). Nao monta detalhe — isso e papel das tools de dimensao; aqui
 * so se mede o suficiente para a triagem apontar a direcao.
 */
public final class JfrCorrelation {

    public record Signals(
            int gcCount,
            long longestGcPauseMs,
            long totalGcPauseMs,
            long totalLockWaitMs,
            long totalAllocBytes
    ) {
    }

    private JfrCorrelation() {
    }

    public static Signals read(Path jfr, Instant capturedAt) throws IOException {
        Acc acc = new Acc();
        JfrSnapshot.forEachEvent(jfr, capturedAt, event -> accumulate(event, acc));
        return new Signals(acc.gcCount, acc.longestGc, acc.totalGc, acc.totalLock, acc.totalAlloc);
    }

    private static void accumulate(RecordedEvent event, Acc acc) {
        switch (event.getEventType().getName()) {
            case "jdk.GarbageCollection" -> {
                acc.gcCount++;
                acc.longestGc = Math.max(acc.longestGc, JfrSnapshot.durationMillis(event, "longestPause"));
                acc.totalGc += JfrSnapshot.durationMillis(event, "sumOfPauses");
            }
            case "jdk.JavaMonitorEnter" -> acc.totalLock += event.getDuration().toMillis();
            case "jdk.ObjectAllocationSample" -> acc.totalAlloc += event.hasField("weight") ? event.getLong("weight") : 0L;
            default -> { /* dimensao irrelevante para a triagem */ }
        }
    }

    private static final class Acc {
        int gcCount;
        long longestGc;
        long totalGc;
        long totalLock;
        long totalAlloc;
    }
}

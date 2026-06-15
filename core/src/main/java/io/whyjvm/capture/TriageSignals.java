package io.whyjvm.capture;

/**
 * Numeros headline de cada dimensao, extraidos numa unica passada barata pelo
 * snapshot. E o que a triagem usa para apontar a dimensao suspeita, sem gastar
 * token. Substitui o antigo {@code JfrCorrelation.Signals}; espelha o schema v1
 * ({@code triageSignals}).
 */
public record TriageSignals(
        int gcCount,
        long longestGcPauseMs,
        long totalGcPauseMs,
        long totalLockWaitMs,
        long totalAllocBytes
) {
}

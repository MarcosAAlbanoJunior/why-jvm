package io.whyjvm.capture;

/**
 * Metadados da JVM no momento do incidente (heap, GC, threads). Null quando nao
 * coletados. Espelha o schema v1 ({@code jvmContext}).
 */
public record JvmContext(
        long heapUsedMb,
        long heapMaxMb,
        String gcName,
        int threadCount
) {
}

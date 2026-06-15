package io.whyjvm.capture;

/**
 * Comportamento normal do endpoint, para a triagem comparar. Null quando ainda
 * nao ha amostras suficientes. Espelha o schema v1 ({@code baseline}).
 */
public record Baseline(
        double p99Ms,
        int sampleCount,
        double thresholdMultiplier
) {
}

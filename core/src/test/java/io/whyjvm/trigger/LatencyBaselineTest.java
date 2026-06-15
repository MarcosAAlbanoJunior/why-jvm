package io.whyjvm.trigger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyBaselineTest {

    private static final int WARMUP = 50;

    @Test
    void neverAnomalousDuringWarmup() {
        LatencyBaseline baseline = new LatencyBaseline(3.0);

        // Mesmo um pico absurdo no inicio nao acusa: sem historico nao da para
        // distinguir lento de normal.
        for (int i = 0; i < WARMUP; i++) {
            assertFalse(baseline.isAnomalousAndRecord("GET /x", 100_000),
                    "nao deve acusar anomalia durante o warmup (amostra " + i + ")");
        }
    }

    @Test
    void flagsRequestWellAboveP99AfterWarmup() {
        LatencyBaseline baseline = new LatencyBaseline(3.0);
        warmUp(baseline, "GET /x", 100); // baseline p99 ~100ms

        assertTrue(baseline.isAnomalousAndRecord("GET /x", 800), "800ms > 3x100ms: lento");
    }

    @Test
    void doesNotFlagRequestWithinFactor() {
        LatencyBaseline baseline = new LatencyBaseline(3.0);
        warmUp(baseline, "GET /x", 100); // baseline p99 ~100ms

        assertFalse(baseline.isAnomalousAndRecord("GET /x", 150), "150ms < 3x100ms");
        assertFalse(baseline.isAnomalousAndRecord("GET /x", 250), "250ms < 3x100ms");
    }

    @Test
    void baselineIsPerEndpoint() {
        LatencyBaseline baseline = new LatencyBaseline(3.0);
        warmUp(baseline, "GET /fast", 50);

        // Endpoint diferente ainda esta em warmup: nao herda o baseline do outro.
        assertFalse(baseline.isAnomalousAndRecord("GET /slow-natty", 5_000));
    }

    private static void warmUp(LatencyBaseline baseline, String endpoint, long durationMs) {
        for (int i = 0; i < WARMUP; i++) {
            baseline.isAnomalousAndRecord(endpoint, durationMs);
        }
    }
}

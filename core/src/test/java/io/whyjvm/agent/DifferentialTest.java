package io.whyjvm.agent;

import io.whyjvm.capture.Dimensions;
import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.ThreadActivity;
import io.whyjvm.capture.TriageSignals;
import io.whyjvm.trigger.IncidentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DifferentialTest {

    private static IncidentRecord slow(long durationMs, TriageSignals sig, ThreadActivity ta) {
        return IncidentRecord.initial(
                        "inc", Instant.now(), "GET /x", IncidentType.SLOW, "fp",
                        ta != null ? ta.thread() : null, durationMs, 1, null, null, null)
                .withEvidence(sig, new Dimensions(null, null, null, ta, null), null);
    }

    @Test
    void externalWaitRulesOutJvmNoise() {
        // Thread dormiu 10s; alocacao alta (143MB) e JVM-wide ruido.
        IncidentRecord rec = slow(10005,
                new TriageSignals(3, 4, 8, 0, 143L * 1024 * 1024),
                new ThreadActivity("http-nio-1", 10000, null, 0, 0, 0, 0));
        String d = String.join(" | ", Differential.of(rec));

        assertTrue(d.contains("Pausas de GC"), d);
        assertTrue(d.contains("Contencao de lock"), d);
        assertTrue(d.contains("CPU: a thread"), d);
        assertTrue(d.contains("ruido de fundo"), d);
        // A espera externa NAO e descartada — e a causa.
        assertFalse(d.contains("Espera externa"), d);
    }

    @Test
    void keepsLiveCandidates() {
        // GC 812ms + thread em CPU (38 amostras) + alocacao alta.
        IncidentRecord rec = slow(4200,
                new TriageSignals(3, 812, 1340, 0, 1288490188L),
                new ThreadActivity("http-nio-3", 0, null, 0, 0, 0, 38));
        String d = String.join(" | ", Differential.of(rec));

        assertTrue(d.contains("Contencao de lock"), d);
        assertTrue(d.contains("Espera externa"), d);
        // Candidatos vivos NAO descartados: GC, CPU, alocacao.
        assertFalse(d.contains("Pausas de GC"), d);
        assertFalse(d.contains("CPU: a thread"), d);
        assertFalse(d.contains("Pressao de alocacao"), d);
    }

    @Test
    void noSignalsRulesOutNothing() {
        IncidentRecord rec = IncidentRecord.initial(
                "inc", Instant.now(), "GET /x", IncidentType.SLOW, "fp", "t", 100, 1, null, null, null);
        assertTrue(Differential.of(rec).isEmpty());
    }
}

package io.whyjvm.capture;

import jdk.jfr.Recording;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifica que a config enxuta (5.5#3) liga exatamente os eventos que as tools
 * leem — e nao o firehose do config {@code profile}.
 */
class JfrTunedConfigTest {

    @Test
    void enablesOnlyTheEvidenceEvents() {
        try (Recording r = new Recording()) {
            JfrEvidenceCapture.enableEvidenceEvents(r);
            Map<String, String> s = r.getSettings();

            String[] needed = {
                    "jdk.GarbageCollection", "jdk.ObjectAllocationSample", "jdk.JavaMonitorEnter",
                    "jdk.ExecutionSample", "jdk.ThreadSleep", "jdk.ThreadPark",
                    "jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite",
            };
            for (String event : needed) {
                assertEquals("true", s.get(event + "#enabled"), event + " deveria estar ligado");
            }

            // ExecutionSample mais espacado que o profile (10ms).
            String period = s.get("jdk.ExecutionSample#period");
            assertNotNull(period, "ExecutionSample deveria ter periodo");
            assertTrue(period.contains("20"), "periodo esperado ~20ms, veio " + period);

            // Eventos caros do profile que NAO ligamos (firehose): nao habilitados.
            assertNotEquals("true", s.get("jdk.ObjectAllocationInNewTLAB#enabled"));
            assertNotEquals("true", s.get("jdk.JavaExceptionThrow#enabled"));
        }
    }
}

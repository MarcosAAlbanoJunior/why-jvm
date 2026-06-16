package io.whyjvm.mcp.tools;

import io.whyjvm.capture.Dimensions;
import io.whyjvm.capture.ExceptionInfo;
import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.InMemoryIncidentStore;
import io.whyjvm.capture.TriageSignals;
import io.whyjvm.mcp.ToolResult;
import io.whyjvm.trigger.IncidentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriageToolTest {

    private static IncidentRecord error(String id, String exceptionType, String message, String stack) {
        return IncidentRecord.initial(
                id, Instant.now(), "POST /checkout", IncidentType.ERROR, "fp-" + id,
                "http-nio-exec-1", 12, 1,
                new ExceptionInfo(exceptionType, message, stack), null, null);
    }

    @Test
    void triageOfErrorPointsToExceptionDimension() {
        IncidentRecord r = error("inc-1", "java.lang.IllegalStateException", "boom",
                "java.lang.IllegalStateException: boom\n\tat com.foo.Bar.doIt(Bar.java:42)");

        String report = TriageTool.report(r);

        assertTrue(report.contains("Dimensao suspeita: exception"), report);
        assertTrue(report.contains("get_exception_details"), report);
        assertTrue(report.contains("java.lang.IllegalStateException"), report);
    }

    @Test
    void triageOfSlowAlwaysConfirmsThreadActivityFirst() {
        // SLOW com GC alto: a triagem NAO deve fechar em GC — get_thread_activity
        // vem primeiro (a dimensao JVM-wide e so candidato).
        TriageSignals sig = new TriageSignals(3, 812, 1340, 0, 1288490188L);
        IncidentRecord r = IncidentRecord.initial(
                        "inc-slow", Instant.now(), "POST /checkout", IncidentType.SLOW, "fp-slow",
                        "http-nio-exec-3", 4200, 1, null, null, null)
                .withEvidence(sig, Dimensions.empty(), null);

        String report = TriageTool.report(r);

        assertTrue(report.contains("Proximo passo sugerido: get_thread_activity"), report);
        assertTrue(report.contains("candidato: gc"), report);
    }

    @Test
    void executeReturnsOkForKnownIncident() {
        InMemoryIncidentStore store = new InMemoryIncidentStore();
        store.save(error("inc-2", "java.lang.RuntimeException", "x", "java.lang.RuntimeException: x"));

        TriageTool tool = new TriageTool(store);
        ToolResult result = tool.execute(Map.of("incidentId", "inc-2"));

        assertFalse(result.isError());
        assertTrue(result.content().contains("TRIAGEM do incidente inc-2"), result.content());
    }

    @Test
    void executeReturnsErrorForUnknownIncident() {
        TriageTool tool = new TriageTool(new InMemoryIncidentStore());

        ToolResult result = tool.execute(Map.of("incidentId", "nope"));

        assertTrue(result.isError());
    }
}

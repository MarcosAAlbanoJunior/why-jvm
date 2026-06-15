package io.whyjvm.mcp.tools;

import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.InMemoryIncidentStore;
import io.whyjvm.mcp.ToolResult;
import io.whyjvm.trigger.IncidentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TriageToolTest {

    @Test
    void triageOfErrorPointsToExceptionDimension() {
        IncidentRecord r = new IncidentRecord(
                "inc-1", Instant.now(), "POST /checkout", IncidentType.ERROR, "fp-1",
                "http-nio-exec-1", 12,
                "java.lang.IllegalStateException", "boom",
                "java.lang.IllegalStateException: boom\n\tat com.foo.Bar.doIt(Bar.java:42)",
                null);

        String report = TriageTool.report(r);

        assertTrue(report.contains("Dimensao suspeita: exception"), report);
        assertTrue(report.contains("get_exception_details"), report);
        assertTrue(report.contains("java.lang.IllegalStateException"), report);
    }

    @Test
    void executeReturnsOkForKnownIncident() {
        InMemoryIncidentStore store = new InMemoryIncidentStore();
        store.save(new IncidentRecord(
                "inc-2", Instant.now(), "GET /x", IncidentType.ERROR, "fp-2",
                "http-nio-exec-2", 5, "java.lang.RuntimeException", "x", "java.lang.RuntimeException: x", null));

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

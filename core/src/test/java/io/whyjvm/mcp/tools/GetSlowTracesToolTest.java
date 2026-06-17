package io.whyjvm.mcp.tools;

import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.InMemoryIncidentStore;
import io.whyjvm.capture.SlowTrace;
import io.whyjvm.trigger.IncidentType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GetSlowTracesToolTest {

    private static IncidentRecord slow(long durationMs, List<SlowTrace> traces) {
        IncidentRecord base = IncidentRecord.initial(
                "inc", Instant.now(), "GET /orders", IncidentType.SLOW, "fp", "t", durationMs, 1, null, null, null);
        return traces == null ? base : base.withSlowTraces(traces);
    }

    @Test
    void rendersDominantSpanAndPercent() {
        InMemoryIncidentStore store = new InMemoryIncidentStore();
        store.save(slow(200, List.of(
                new SlowTrace("findById", 140, 140),
                new SlowTrace("GET /orders", 60, 200))));

        String out = new GetSlowTracesTool(store).execute(Map.of("incidentId", "inc")).content();

        assertTrue(out.contains("findById"), out);
        assertTrue(out.contains("self 140ms"), out);
        assertTrue(out.contains("70% da latencia"), out); // 140/200
    }

    @Test
    void callsOutNPlusOne() {
        InMemoryIncidentStore store = new InMemoryIncidentStore();
        store.save(slow(80, List.of(
                new SlowTrace("N+1: SELECT orders ×12", 60, 60),
                new SlowTrace("GET /orders", 20, 80))));

        String out = new GetSlowTracesTool(store).execute(Map.of("incidentId", "inc")).content();

        assertTrue(out.contains("N+1: SELECT orders ×12"), out);
        assertTrue(out.contains("N+1 detectado"), out);
    }

    @Test
    void honestWhenNoTrace() {
        InMemoryIncidentStore store = new InMemoryIncidentStore();
        store.save(slow(100, null));

        String out = new GetSlowTracesTool(store).execute(Map.of("incidentId", "inc")).content();

        assertTrue(out.contains("Sem arvore de trace"), out);
    }
}

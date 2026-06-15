package io.whyjvm.mcp.tools;

import io.whyjvm.mcp.tools.GetAllocationHotspotsTool.AllocSample;
import io.whyjvm.mcp.tools.GetGcActivityTool.GcPause;
import io.whyjvm.mcp.tools.GetLockContentionTool.LockWait;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa a logica de agregacao das tools de dimensao isolada do parsing JFR:
 * cada {@code summarize} opera sobre value objects simples, entao e
 * deterministico (sem depender de amostragem do JFR no ambiente).
 */
class JfrAggregatorsTest {

    @Test
    void gcSummaryHighlightsLongestPauseAndTotal() {
        String out = GetGcActivityTool.summarize(List.of(
                new GcPause("G1New", "Allocation Failure", 120, 120),
                new GcPause("G1Full", "Allocation Failure", 812, 900)));

        assertTrue(out.contains("Coletas de GC na janela: 2"), out);
        assertTrue(out.contains("Maior pausa: 812ms"), out);
        assertTrue(out.contains("Pausa total: 1020ms"), out);
    }

    @Test
    void gcSummaryHandlesEmpty() {
        assertTrue(GetGcActivityTool.summarize(List.of()).contains("Nenhuma coleta"));
    }

    @Test
    void allocationSummaryRanksAndAggregatesBySite() {
        // buildLineItems domina; duas amostras dele somam.
        String out = GetAllocationHotspotsTool.summarize(List.of(
                new AllocSample("com.foo.InvoiceBuilder.buildLineItems", 700 * 1024),
                new AllocSample("com.foo.InvoiceBuilder.buildLineItems", 500 * 1024),
                new AllocSample("com.foo.Other.thing", 100 * 1024)), 5);

        // 1200KB de 1300KB total = 92%, e deve vir primeiro.
        int posBuild = out.indexOf("buildLineItems");
        int posOther = out.indexOf("Other.thing");
        assertTrue(posBuild >= 0 && posOther > posBuild, "site dominante primeiro: " + out);
        assertTrue(out.contains("buildLineItems: 1200 KB (92%)"), out);
    }

    @Test
    void lockSummaryTotalsWaitPerSite() {
        String out = GetLockContentionTool.summarize(List.of(
                new LockWait("com.foo.Cache.get", "com.foo.Cache", 30),
                new LockWait("com.foo.Cache.get", "com.foo.Cache", 50),
                new LockWait("com.foo.Pool.lease", "com.foo.Pool", 10)), 5);

        assertTrue(out.contains("Espera total: 90ms em 3 eventos"), out);
        assertTrue(out.contains("com.foo.Cache.get (monitor com.foo.Cache): 80ms"), out);
    }
}

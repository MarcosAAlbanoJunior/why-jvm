package io.whyjvm.mcp.tools;

import io.whyjvm.capture.AllocationHotspots;
import io.whyjvm.capture.EvidenceExtractor;
import io.whyjvm.capture.EvidenceExtractor.AllocSample;
import io.whyjvm.capture.EvidenceExtractor.LockSample;
import io.whyjvm.capture.GcActivity;
import io.whyjvm.capture.GcActivity.Pause;
import io.whyjvm.capture.LockContention;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testa a agregacao (folds puros do {@link EvidenceExtractor}) e a renderizacao
 * (summarize das tools) isoladas do parsing JFR: tudo opera sobre value objects
 * simples, entao e deterministico (sem depender da amostragem do JFR no ambiente).
 */
class JfrAggregatorsTest {

    @Test
    void gcSummaryHighlightsLongestPauseAndTotal() {
        GcActivity gc = EvidenceExtractor.gcActivity(List.of(
                new Pause("G1New", "Allocation Failure", 120, 120),
                new Pause("G1Full", "Allocation Failure", 812, 900)));

        String out = GetGcActivityTool.summarize(gc);

        assertTrue(out.contains("Coletas de GC na janela: 2"), out);
        assertTrue(out.contains("Maior pausa: 812ms"), out);
        assertTrue(out.contains("Pausa total: 1020ms"), out);
    }

    @Test
    void gcSummaryHandlesEmpty() {
        assertTrue(GetGcActivityTool.summarize(EvidenceExtractor.gcActivity(List.of())).contains("Nenhuma coleta"));
    }

    @Test
    void allocationSummaryRanksAndAggregatesBySite() {
        // buildLineItems domina; duas amostras dele somam.
        AllocationHotspots alloc = EvidenceExtractor.allocHotspots(List.of(
                new AllocSample("com.foo.InvoiceBuilder.buildLineItems", 700 * 1024),
                new AllocSample("com.foo.InvoiceBuilder.buildLineItems", 500 * 1024),
                new AllocSample("com.foo.Other.thing", 100 * 1024)), 5);

        String out = GetAllocationHotspotsTool.summarize(alloc);

        // 1200KB de 1300KB total = 92%, e deve vir primeiro.
        int posBuild = out.indexOf("buildLineItems");
        int posOther = out.indexOf("Other.thing");
        assertTrue(posBuild >= 0 && posOther > posBuild, "site dominante primeiro: " + out);
        assertTrue(out.contains("buildLineItems: 1200 KB (92%)"), out);
    }

    @Test
    void lockSummaryTotalsWaitPerSite() {
        LockContention lock = EvidenceExtractor.lockContention(List.of(
                new LockSample("com.foo.Cache.get", "com.foo.Cache", 30),
                new LockSample("com.foo.Cache.get", "com.foo.Cache", 50),
                new LockSample("com.foo.Pool.lease", "com.foo.Pool", 10)), 5);

        String out = GetLockContentionTool.summarize(lock);

        assertTrue(out.contains("Espera total: 90ms em 3 eventos"), out);
        assertTrue(out.contains("com.foo.Cache.get (monitor com.foo.Cache): 80ms"), out);
    }
}

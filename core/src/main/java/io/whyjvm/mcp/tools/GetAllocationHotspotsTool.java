package io.whyjvm.mcp.tools;

import io.whyjvm.capture.AllocationHotspots;
import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.IncidentStore;

/**
 * Hotspots de alocacao na janela: Top N de call sites por bytes alocados — o
 * "buildLineItems responde por 73% das alocacoes". Le o agregado ja extraido
 * ({@link AllocationHotspots}, amostrado via jdk.ObjectAllocationSample, JDK 16+).
 */
public final class GetAllocationHotspotsTool extends DimensionTool {

    public GetAllocationHotspotsTool(IncidentStore store) {
        super(store);
    }

    @Override
    public String name() {
        return "get_allocation_hotspots";
    }

    @Override
    public String description() {
        return "Top call sites por bytes alocados na janela (amostrado via JFR).";
    }

    @Override
    protected String render(IncidentRecord record) {
        return summarize(record.dimensions().allocationHotspots());
    }

    static String summarize(AllocationHotspots alloc) {
        if (alloc == null || alloc.topSites().isEmpty()) {
            return "Nenhuma amostra de alocacao na janela.";
        }
        StringBuilder sb = new StringBuilder(
                "Total amostrado na janela: %d KB em %d amostras (avalie se a magnitude e relevante frente a latencia; KB poucos sao ruido de fundo, nao causa).\n"
                        .formatted(alloc.totalSampledBytes() / 1024, alloc.sampleCount()));
        sb.append("Top call sites por bytes alocados (amostrado):\n");
        for (AllocationHotspots.Site s : alloc.topSites()) {
            sb.append("- %s: %d KB (%d%%)\n".formatted(s.site(), s.bytes() / 1024, Math.round(s.pct())));
        }
        return sb.toString();
    }
}

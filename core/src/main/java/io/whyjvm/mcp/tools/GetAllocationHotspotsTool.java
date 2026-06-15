package io.whyjvm.mcp.tools;

import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.IncidentStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fase 3: hotspots de alocacao na janela. Le {@code jdk.ObjectAllocationSample}
 * (amostrado, JDK 16+) e devolve o Top N de call sites por bytes alocados — o
 * "buildLineItems responde por 73% das alocacoes".
 */
public final class GetAllocationHotspotsTool extends JfrDimensionTool {

    private static final int TOP_N = 5;

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
    protected String aggregate(IncidentRecord record, Path jfr) throws IOException {
        List<AllocSample> samples = new ArrayList<>();
        JfrSnapshot.forEachEvent(jfr, record.capturedAt(), event -> {
            if (!"jdk.ObjectAllocationSample".equals(event.getEventType().getName())) {
                return;
            }
            String site = JfrSnapshot.topFrame(event);
            long weight = event.hasField("weight") ? event.getLong("weight") : 0L;
            if (site != null && weight > 0) {
                samples.add(new AllocSample(site, weight));
            }
        });
        return summarize(samples, TOP_N);
    }

    record AllocSample(String site, long bytes) {
    }

    static String summarize(List<AllocSample> samples, int topN) {
        if (samples.isEmpty()) {
            return "Nenhuma amostra de alocacao na janela.";
        }
        Map<String, Long> bySite = new LinkedHashMap<>();
        for (AllocSample s : samples) {
            bySite.merge(s.site(), s.bytes(), Long::sum);
        }
        long total = bySite.values().stream().mapToLong(Long::longValue).sum();

        StringBuilder sb = new StringBuilder(
                "Total amostrado na janela: %d KB em %d amostras (avalie se a magnitude e relevante "
                        + "frente a latencia; KB poucos sao ruido de fundo, nao causa).\n"
                        .formatted(total / 1024, samples.size()));
        sb.append("Top call sites por bytes alocados (amostrado):\n");
        bySite.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(topN)
                .forEach(e -> sb.append("- %s: %d KB (%d%%)\n"
                        .formatted(e.getKey(), e.getValue() / 1024, e.getValue() * 100 / total)));
        return sb.toString();
    }
}

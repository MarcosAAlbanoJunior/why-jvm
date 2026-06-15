package io.whyjvm.mcp.tools;

import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.IncidentStore;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Fase 3: pausas de GC na janela do incidente. Le {@code jdk.GarbageCollection}
 * e devolve um agregado: numero de coletas, pausa total e a maior pausa com a
 * causa — o "pausa de GC de 812ms" que costuma explicar a lentidao.
 */
public final class GetGcActivityTool extends JfrDimensionTool {

    public GetGcActivityTool(IncidentStore store) {
        super(store);
    }

    @Override
    public String name() {
        return "get_gc_activity";
    }

    @Override
    public String description() {
        return "Pausas de GC na janela: numero de coletas, pausa total e a maior pausa com a causa.";
    }

    @Override
    protected String aggregate(IncidentRecord record, Path jfr) throws IOException {
        List<GcPause> pauses = new ArrayList<>();
        JfrSnapshot.forEachEvent(jfr, record.capturedAt(), event -> {
            if (!"jdk.GarbageCollection".equals(event.getEventType().getName())) {
                return;
            }
            pauses.add(new GcPause(
                    event.hasField("name") ? event.getString("name") : "GC",
                    event.hasField("cause") ? event.getString("cause") : "?",
                    JfrSnapshot.durationMillis(event, "longestPause"),
                    JfrSnapshot.durationMillis(event, "sumOfPauses")));
        });
        return summarize(pauses);
    }

    record GcPause(String name, String cause, long longestPauseMs, long sumPausesMs) {
    }

    static String summarize(List<GcPause> pauses) {
        if (pauses.isEmpty()) {
            return "Nenhuma coleta de GC na janela.";
        }
        long totalPause = pauses.stream().mapToLong(GcPause::sumPausesMs).sum();
        GcPause worst = pauses.stream().max(Comparator.comparingLong(GcPause::longestPauseMs)).orElseThrow();
        return """
                Coletas de GC na janela: %d
                Pausa total: %dms
                Maior pausa: %dms (%s, causa: %s)
                """.formatted(pauses.size(), totalPause, worst.longestPauseMs(), worst.name(), worst.cause());
    }
}

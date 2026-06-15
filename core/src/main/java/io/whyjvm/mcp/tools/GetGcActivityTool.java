package io.whyjvm.mcp.tools;

import io.whyjvm.capture.GcActivity;
import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.IncidentStore;

import java.util.Comparator;

/**
 * Pausas de GC na janela do incidente: numero de coletas, pausa total e a maior
 * pausa com a causa — o "pausa de GC de 812ms" que costuma explicar a lentidao.
 * Le o agregado ja extraido ({@link GcActivity}); nao toca no JFR.
 */
public final class GetGcActivityTool extends DimensionTool {

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
    protected String render(IncidentRecord record) {
        return summarize(record.dimensions().gcActivity());
    }

    static String summarize(GcActivity gc) {
        if (gc == null || gc.pauses().isEmpty()) {
            return "Nenhuma coleta de GC na janela.";
        }
        GcActivity.Pause worst = gc.pauses().stream()
                .max(Comparator.comparingLong(GcActivity.Pause::longestPauseMs))
                .orElseThrow();
        return """
                Coletas de GC na janela: %d
                Pausa total: %dms
                Maior pausa: %dms (%s, causa: %s)
                """.formatted(gc.count(), gc.totalPauseMs(), worst.longestPauseMs(), worst.name(), worst.cause());
    }
}

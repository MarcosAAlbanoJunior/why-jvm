package io.whyjvm.mcp.tools;

import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.IncidentStore;
import io.whyjvm.capture.LockContention;

/**
 * Contencao de lock na janela: os call sites que mais esperaram em monitor, com o
 * tempo total de espera. Le o agregado ja extraido ({@link LockContention},
 * jdk.JavaMonitorEnter); nao toca no JFR.
 */
public final class GetLockContentionTool extends DimensionTool {

    public GetLockContentionTool(IncidentStore store) {
        super(store);
    }

    @Override
    public String name() {
        return "get_lock_contention";
    }

    @Override
    public String description() {
        return "Threads bloqueadas em monitor na janela: call sites por tempo de espera.";
    }

    @Override
    protected String render(IncidentRecord record) {
        return summarize(record.dimensions().lockContention());
    }

    static String summarize(LockContention lock) {
        if (lock == null || lock.topSites().isEmpty()) {
            return "Nenhuma contencao de lock relevante na janela.";
        }
        StringBuilder sb = new StringBuilder("Contencao de lock na janela:\n");
        sb.append("Espera total: %dms em %d eventos\n".formatted(lock.totalWaitMs(), lock.eventCount()));
        for (LockContention.Site s : lock.topSites()) {
            sb.append("- %s (monitor %s): %dms\n".formatted(s.site(), s.monitorClass(), s.waitMs()));
        }
        return sb.toString();
    }
}

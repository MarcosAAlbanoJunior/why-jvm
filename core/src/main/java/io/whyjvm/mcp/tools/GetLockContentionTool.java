package io.whyjvm.mcp.tools;

import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.IncidentStore;
import jdk.jfr.consumer.RecordedClass;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fase 3: contencao de lock na janela. Le {@code jdk.JavaMonitorEnter} e devolve
 * os call sites que mais esperaram em monitor, com o tempo total de espera.
 */
public final class GetLockContentionTool extends JfrDimensionTool {

    private static final int TOP_N = 5;

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
    protected String aggregate(IncidentRecord record, Path jfr) throws IOException {
        List<LockWait> waits = new ArrayList<>();
        JfrSnapshot.forEachEvent(jfr, record.capturedAt(), event -> {
            if (!"jdk.JavaMonitorEnter".equals(event.getEventType().getName())) {
                return;
            }
            String site = JfrSnapshot.topFrame(event);
            if (site == null) {
                return;
            }
            String monitor = "?";
            if (event.hasField("monitorClass")) {
                RecordedClass c = event.getClass("monitorClass");
                if (c != null) {
                    monitor = c.getName();
                }
            }
            waits.add(new LockWait(site, monitor, event.getDuration().toMillis()));
        });
        return summarize(waits, TOP_N);
    }

    record LockWait(String site, String monitorClass, long waitMs) {
    }

    static String summarize(List<LockWait> waits, int topN) {
        if (waits.isEmpty()) {
            return "Nenhuma contencao de lock relevante na janela.";
        }
        Map<String, Long> waitBySite = new LinkedHashMap<>();
        Map<String, String> monitorBySite = new HashMap<>();
        for (LockWait w : waits) {
            waitBySite.merge(w.site(), w.waitMs(), Long::sum);
            monitorBySite.putIfAbsent(w.site(), w.monitorClass());
        }
        long total = waitBySite.values().stream().mapToLong(Long::longValue).sum();

        StringBuilder sb = new StringBuilder("Contencao de lock na janela:\n");
        sb.append("Espera total: %dms em %d eventos\n".formatted(total, waits.size()));
        waitBySite.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(topN)
                .forEach(e -> sb.append("- %s (monitor %s): %dms\n"
                        .formatted(e.getKey(), monitorBySite.get(e.getKey()), e.getValue())));
        return sb.toString();
    }
}

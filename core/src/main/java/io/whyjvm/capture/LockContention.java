package io.whyjvm.capture;

import java.util.List;

/**
 * Call sites por tempo de espera em monitor, {@code jdk.JavaMonitorEnter}
 * (dimensao {@code lockContention}).
 */
public record LockContention(
        long totalWaitMs,
        int eventCount,
        List<Site> topSites
) {
    /** Um call site que esperou um monitor e por quanto tempo. */
    public record Site(
            String site,
            String monitorClass,
            long waitMs
    ) {
    }
}

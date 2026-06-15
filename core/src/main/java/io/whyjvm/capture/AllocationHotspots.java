package io.whyjvm.capture;

import java.util.List;

/**
 * Top call sites por bytes alocados, amostrado via {@code jdk.ObjectAllocationSample}
 * (dimensao {@code allocationHotspots}).
 */
public record AllocationHotspots(
        long totalSampledBytes,
        int sampleCount,
        List<Site> topSites
) {
    /** Um call site e quanto alocou na janela. */
    public record Site(
            String site,
            long bytes,
            double pct
    ) {
    }
}

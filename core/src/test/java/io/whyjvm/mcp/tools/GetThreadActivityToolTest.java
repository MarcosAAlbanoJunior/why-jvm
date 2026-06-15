package io.whyjvm.mcp.tools;

import io.whyjvm.mcp.tools.GetThreadActivityTool.Activity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GetThreadActivityToolTest {

    @Test
    void identifiesWaitWhenSleepDominates() {
        Activity a = new Activity();
        a.sleepMs = 10_000;
        a.sleepSite = "io.whyjvm.sample.DemoController.searchOrders";
        a.cpuSamples = 0;

        String out = GetThreadActivityTool.summarize("http-nio-8080-exec-3", 10_010, a);

        assertTrue(out.contains("Thread.sleep: 10000ms"), out);
        assertTrue(out.contains("searchOrders"), out);
        assertTrue(out.contains("ESPERANDO"), out);
    }

    @Test
    void identifiesCpuWorkWhenSamplesDominate() {
        Activity a = new Activity();
        a.cpuSamples = 40;

        String out = GetThreadActivityTool.summarize("worker-1", 500, a);

        assertTrue(out.contains("majoritariamente em CPU"), out);
    }

    @Test
    void pointsToIoWhenIoDominates() {
        Activity a = new Activity();
        a.ioMs = 9_800;

        String out = GetThreadActivityTool.summarize("http-nio-8080-exec-5", 10_000, a);

        assertTrue(out.contains("ESPERANDO (I/O)"), out);
    }
}

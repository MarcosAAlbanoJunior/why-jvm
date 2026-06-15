package io.whyjvm.mcp.tools;

import io.whyjvm.capture.ThreadActivity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GetThreadActivityToolTest {

    @Test
    void identifiesWaitWhenSleepDominates() {
        ThreadActivity a = new ThreadActivity(
                "http-nio-8080-exec-3", 10_000,
                "io.whyjvm.sample.DemoController.searchOrders", 0, 0, 0, 0);

        String out = GetThreadActivityTool.summarize(a, 10_010);

        assertTrue(out.contains("Thread.sleep: 10000ms"), out);
        assertTrue(out.contains("searchOrders"), out);
        assertTrue(out.contains("ESPERANDO"), out);
    }

    @Test
    void identifiesCpuWorkWhenSamplesDominate() {
        ThreadActivity a = new ThreadActivity("worker-1", 0, null, 0, 0, 0, 40);

        String out = GetThreadActivityTool.summarize(a, 500);

        assertTrue(out.contains("majoritariamente em CPU"), out);
    }

    @Test
    void pointsToIoWhenIoDominates() {
        ThreadActivity a = new ThreadActivity("http-nio-8080-exec-5", 0, null, 9_800, 0, 0, 0);

        String out = GetThreadActivityTool.summarize(a, 10_000);

        assertTrue(out.contains("ESPERANDO (I/O)"), out);
    }
}

package io.whyjvm.trigger;

import io.whyjvm.capture.SpanNode;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceSpanBufferTest {

    @Test
    void recordsAndCollectsByTrace() {
        TraceSpanBuffer buf = new TraceSpanBuffer();
        buf.record("t1", "root", null, "GET /x", 100);
        buf.record("t1", "c1", "root", "findById", 70);
        buf.record("t2", "other", null, "GET /y", 10);

        List<SpanNode> t1 = buf.collectAndEvict("t1");

        assertEquals(2, t1.size());
        assertTrue(t1.stream().anyMatch(s -> s.name().equals("findById")), t1.toString());
        // coletar removeu t1; t2 segue
        assertEquals(0, buf.collectAndEvict("t1").size());
        assertEquals(1, buf.size());
    }

    @Test
    void evictDropsTraceWithoutCollecting() {
        TraceSpanBuffer buf = new TraceSpanBuffer();
        buf.record("t1", "root", null, "GET /x", 100);
        buf.evict("t1");
        assertEquals(0, buf.size());
        assertEquals(0, buf.collectAndEvict("t1").size());
    }

    @Test
    void capsSpansPerTrace() {
        TraceSpanBuffer buf = new TraceSpanBuffer(10, 3, Duration.ofSeconds(60));
        for (int i = 0; i < 100; i++) {
            buf.record("t1", "s" + i, "root", "leaf", 1);
        }
        assertEquals(3, buf.collectAndEvict("t1").size());
    }

    @Test
    void capsNumberOfTraces() {
        TraceSpanBuffer buf = new TraceSpanBuffer(4, 100, Duration.ofSeconds(60));
        for (int i = 0; i < 50; i++) {
            buf.record("trace-" + i, "root", null, "GET /x", 1);
        }
        assertTrue(buf.size() <= 4, "buffer deveria respeitar o cap de traces: " + buf.size());
    }

    @Test
    void evictsExpiredOnPressure() {
        TraceSpanBuffer buf = new TraceSpanBuffer(2, 100, Duration.ofNanos(1));
        buf.record("old", "root", null, "GET /x", 1);
        // TTL de 1ns: ao inserir um novo trace no cap, o expirado some.
        buf.record("a", "root", null, "GET /a", 1);
        buf.record("b", "root", null, "GET /b", 1);
        assertEquals(0, buf.collectAndEvict("old").size(), "trace expirado deveria ter sido removido");
    }

    @Test
    void concurrentRecordingIsSafe() throws Exception {
        TraceSpanBuffer buf = new TraceSpanBuffer();
        int threads = 8;
        int perThread = 500;
        List<Thread> workers = new java.util.ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            Thread w = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    buf.record("trace-" + tid, "s" + i, "root", "leaf", 1);
                }
            });
            workers.add(w);
            w.start();
        }
        for (Thread w : workers) {
            w.join();
        }
        // cada trace recebeu perThread spans (abaixo do cap default), sem perda/erro.
        for (int t = 0; t < threads; t++) {
            assertEquals(perThread, buf.collectAndEvict("trace-" + t).size());
        }
    }
}

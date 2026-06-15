package io.whyjvm.capture;

import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifica a leitura do snapshot (o "glue" sobre RecordingFile) de forma
 * deterministica: grava eventos JFR customizados e os le de volta. Nao depende
 * de amostragem de GC/alocacao, que seria flaky.
 */
class JfrSnapshotTest {

    @Name("test.Marker")
    @Label("Marker")
    static class Marker extends Event {
        @Label("n")
        int n;
    }

    @Test
    void readsBackRecordedEventsWithinWindow(@TempDir Path dir) throws Exception {
        Path jfr = dir.resolve("snapshot.jfr");
        try (Recording recording = new Recording()) {
            recording.enable("test.Marker");
            recording.start();
            for (int i = 0; i < 5; i++) {
                Marker marker = new Marker();
                marker.n = i;
                marker.commit();
            }
            recording.stop();
            recording.dump(jfr);
        }

        List<RecordedEvent> seen = new ArrayList<>();
        JfrSnapshot.forEachEvent(jfr, Instant.now().plusSeconds(1), event -> {
            if ("test.Marker".equals(event.getEventType().getName())) {
                seen.add(event);
            }
        });

        assertEquals(5, seen.size());
    }
}

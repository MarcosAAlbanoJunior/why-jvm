package io.whyjvm.mcp.tools;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Leitura do snapshot JFR de um incidente. As tools de dimensao (GC, alocacao,
 * lock) iteram os eventos via {@link RecordingFile} e <b>agregam</b> — nunca
 * devolvem evento cru (Portao 2: e onde o custo de token escaparia).
 */
final class JfrSnapshot {

    /** Janela de evidencia lida em torno do instante do incidente. */
    static final Duration WINDOW = Duration.ofSeconds(60);

    private JfrSnapshot() {
    }

    /** Itera os eventos do arquivo cujo fim cai em {@code [to - WINDOW, to]}. */
    static void forEachEvent(Path jfr, Instant to, Consumer<RecordedEvent> consumer) throws IOException {
        Instant from = to.minus(WINDOW);
        try (RecordingFile rf = new RecordingFile(jfr)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                Instant t = event.getEndTime();
                if (t.isBefore(from) || t.isAfter(to)) {
                    continue;
                }
                consumer.accept(event);
            }
        }
    }

    /** Le um campo do tipo Duration em milissegundos, 0 se ausente. */
    static long durationMillis(RecordedEvent event, String field) {
        return event.hasField(field) ? event.getDuration(field).toMillis() : 0L;
    }

    /** Topo do stack trace: o primeiro frame Java, como {@code pacote.Classe.metodo}. */
    static String topFrame(RecordedEvent event) {
        RecordedStackTrace stack = event.getStackTrace();
        if (stack == null) {
            return null;
        }
        for (RecordedFrame frame : stack.getFrames()) {
            if (!frame.isJavaFrame()) {
                continue;
            }
            RecordedMethod method = frame.getMethod();
            if (method != null) {
                return method.getType().getName() + "." + method.getName();
            }
        }
        return null;
    }
}

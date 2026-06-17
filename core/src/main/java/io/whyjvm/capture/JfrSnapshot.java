package io.whyjvm.capture;

import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * Leitura do snapshot JFR de um incidente. Helpers usados pelo
 * {@link EvidenceExtractor} para iterar os eventos da janela e ler campos
 * comuns. As tools nao leem mais JFR — consomem os agregados ja extraidos.
 */
final class JfrSnapshot {

    private JfrSnapshot() {
    }

    /**
     * Itera os eventos <b>inteiramente contidos</b> na janela {@code [from, to]} —
     * start E end dentro do intervalo. Filtrar pelo start (nao so pelo fim) e o que
     * exclui a espera ANTERIOR ao request: uma thread reaproveitada do pool fica em
     * {@code park} ociosa entre requests, e esse park termina no inicio do request;
     * contar so o que comecou dentro da janela atribui a atividade ao request certo.
     */
    static void forEachEvent(Path jfr, Instant from, Instant to, Consumer<RecordedEvent> consumer)
            throws IOException {
        try (RecordingFile rf = new RecordingFile(jfr)) {
            while (rf.hasMoreEvents()) {
                RecordedEvent event = rf.readEvent();
                if (event.getStartTime().isBefore(from) || event.getEndTime().isAfter(to)) {
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

    /** Nome da thread do evento; para ExecutionSample, o campo {@code sampledThread}. */
    static String threadName(RecordedEvent event) {
        RecordedThread thread = event.getThread();
        if (thread == null && event.hasField("sampledThread")
                && event.getValue("sampledThread") instanceof RecordedThread sampled) {
            thread = sampled;
        }
        return thread != null ? thread.getJavaName() : null;
    }
}

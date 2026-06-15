package io.whyjvm.mcp.tools;

import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.IncidentStore;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedThread;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Fase 3: o que a <b>thread do request lento</b> estava fazendo na janela.
 *
 * <p>Diferente das tools de GC/alocacao — que somam a JVM inteira e por isso
 * confundem ruido de fundo com causa — esta filtra os eventos pela thread que
 * atendeu o incidente e contabiliza o tempo: sleep, I/O, lock, park e amostras
 * de CPU. E o que distingue "lento por trabalho na JVM" de "lento por espera
 * externa" (sleep, query de banco, downstream). Sem isso o agente nao tem como
 * saber que a request ficou bloqueada esperando.
 */
public final class GetThreadActivityTool extends JfrDimensionTool {

    public GetThreadActivityTool(IncidentStore store) {
        super(store);
    }

    @Override
    public String name() {
        return "get_thread_activity";
    }

    @Override
    public String description() {
        return "O que a thread do request fez na janela: tempo em sleep, I/O, lock, park e CPU. "
                + "Use para distinguir lentidao por espera (externa) de lentidao por trabalho na JVM.";
    }

    @Override
    protected String aggregate(IncidentRecord record, Path jfr) throws IOException {
        String target = record.threadName();
        if (target == null) {
            return "Thread do incidente nao registrada; sem como atribuir a atividade.";
        }
        Activity activity = new Activity();
        JfrSnapshot.forEachEvent(jfr, record.capturedAt(), event -> accumulate(event, target, activity));
        return summarize(target, record.durationMs(), activity);
    }

    private static void accumulate(RecordedEvent event, String target, Activity a) {
        if (!target.equals(threadName(event))) {
            return;
        }
        switch (event.getEventType().getName()) {
            case "jdk.ThreadSleep" -> {
                a.sleepMs += event.getDuration().toMillis();
                if (a.sleepSite == null) {
                    a.sleepSite = JfrSnapshot.topFrame(event);
                }
            }
            case "jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite" ->
                    a.ioMs += event.getDuration().toMillis();
            case "jdk.JavaMonitorEnter" -> a.lockMs += event.getDuration().toMillis();
            case "jdk.ThreadPark" -> a.parkMs += event.getDuration().toMillis();
            case "jdk.ExecutionSample" -> a.cpuSamples++;
            default -> { /* irrelevante para a atividade da thread */ }
        }
    }

    /** Nome da thread do evento; para ExecutionSample, o campo {@code sampledThread}. */
    private static String threadName(RecordedEvent event) {
        RecordedThread thread = event.getThread();
        if (thread == null && event.hasField("sampledThread")
                && event.getValue("sampledThread") instanceof RecordedThread sampled) {
            thread = sampled;
        }
        return thread != null ? thread.getJavaName() : null;
    }

    static String summarize(String thread, long incidentMs, Activity a) {
        long waiting = a.sleepMs + a.ioMs + a.lockMs + a.parkMs;
        String conclusion;
        if (a.cpuSamples == 0 && waiting >= incidentMs * 0.5) {
            conclusion = "A thread passou a maior parte ESPERANDO (" + dominant(a) + "), nao executando "
                    + "trabalho na JVM. A lentidao e de espera/bloqueio — nao de CPU, alocacao ou GC desta thread.";
        } else if (a.cpuSamples > 0 && waiting < incidentMs * 0.5) {
            conclusion = "A thread esteve majoritariamente em CPU (" + a.cpuSamples + " amostras); investigue o "
                    + "trabalho/algoritmo desta thread (alocacao/execucao), nao espera externa.";
        } else {
            conclusion = "Atividade mista entre espera e CPU; pondere o dominante (" + dominant(a) + ").";
        }
        return """
                Atividade da thread %s (latencia do incidente: %dms):
                - Thread.sleep: %dms%s
                - Espera de I/O (socket/arquivo): %dms
                - Espera de lock (monitor): %dms
                - Park (LockSupport/concorrencia): %dms
                - Amostras de CPU: %d

                %s
                """.formatted(
                thread, incidentMs,
                a.sleepMs, a.sleepSite != null ? " (em " + a.sleepSite + ")" : "",
                a.ioMs, a.lockMs, a.parkMs, a.cpuSamples, conclusion);
    }

    private static String dominant(Activity a) {
        long max = Math.max(Math.max(a.sleepMs, a.ioMs), Math.max(a.lockMs, a.parkMs));
        if (max == 0) {
            return "sem espera medida";
        }
        if (max == a.sleepMs) {
            return "Thread.sleep";
        }
        if (max == a.ioMs) {
            return "I/O";
        }
        if (max == a.lockMs) {
            return "lock";
        }
        return "park";
    }

    /** Acumulador mutavel da atividade da thread na janela. */
    static final class Activity {
        long sleepMs;
        String sleepSite;
        long ioMs;
        long lockMs;
        long parkMs;
        int cpuSamples;
    }
}

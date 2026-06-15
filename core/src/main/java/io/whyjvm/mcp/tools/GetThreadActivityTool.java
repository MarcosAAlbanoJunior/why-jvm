package io.whyjvm.mcp.tools;

import io.whyjvm.capture.IncidentRecord;
import io.whyjvm.capture.IncidentStore;
import io.whyjvm.capture.ThreadActivity;

/**
 * O que a <b>thread do request lento</b> estava fazendo na janela. Diferente das
 * tools de GC/alocacao — que somam a JVM inteira e confundem ruido de fundo com
 * causa — esta filtra pela thread que atendeu o incidente e contabiliza o tempo:
 * sleep, I/O, lock, park e amostras de CPU. Distingue "lento por trabalho na JVM"
 * de "lento por espera externa" (sleep, query de banco, downstream).
 *
 * <p>Le o agregado ja extraido ({@link ThreadActivity}); nao toca no JFR.
 */
public final class GetThreadActivityTool extends DimensionTool {

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
    protected String render(IncidentRecord record) {
        ThreadActivity activity = record.dimensions().threadActivity();
        if (activity == null) {
            return "Thread do incidente nao registrada (ou sem snapshot); sem como atribuir a atividade.";
        }
        return summarize(activity, record.durationMs());
    }

    static String summarize(ThreadActivity a, long incidentMs) {
        long waiting = a.sleepMs() + a.ioMs() + a.lockMs() + a.parkMs();
        String conclusion;
        if (a.cpuSamples() == 0 && waiting >= incidentMs * 0.5) {
            conclusion = "A thread passou a maior parte ESPERANDO (" + dominant(a) + "), nao executando "
                    + "trabalho na JVM. A lentidao e de espera/bloqueio — nao de CPU, alocacao ou GC desta thread.";
        } else if (a.cpuSamples() > 0 && waiting < incidentMs * 0.5) {
            conclusion = "A thread esteve majoritariamente em CPU (" + a.cpuSamples() + " amostras); investigue o "
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
                a.thread(), incidentMs,
                a.sleepMs(), a.sleepSite() != null ? " (em " + a.sleepSite() + ")" : "",
                a.ioMs(), a.lockMs(), a.parkMs(), a.cpuSamples(), conclusion);
    }

    private static String dominant(ThreadActivity a) {
        long max = Math.max(Math.max(a.sleepMs(), a.ioMs()), Math.max(a.lockMs(), a.parkMs()));
        if (max == 0) {
            return "sem espera medida";
        }
        if (max == a.sleepMs()) {
            return "Thread.sleep";
        }
        if (max == a.ioMs()) {
            return "I/O";
        }
        if (max == a.lockMs()) {
            return "lock";
        }
        return "park";
    }
}

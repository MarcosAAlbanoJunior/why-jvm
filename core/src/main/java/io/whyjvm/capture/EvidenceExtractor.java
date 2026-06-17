package io.whyjvm.capture;

import jdk.jfr.consumer.RecordedClass;
import jdk.jfr.consumer.RecordedEvent;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Extrai os agregados de um snapshot JFR numa <b>unica passada</b> pela janela do
 * incidente. Substitui as N passadas que antes cada tool fazia sob demanda (e a
 * passada extra do antigo {@code JfrCorrelation} da triagem): a leitura do JFR
 * roda uma vez, na captura, e o resultado fica congelado no {@link IncidentRecord}.
 *
 * <p>E o ponto onde o lado Java cumpre sua parte do split: o parsing de JFR (API
 * nativa da JVM) acontece aqui; o que viaja para o servico Go e so o resultado.
 *
 * <p>A agregacao por site (merge, ranking, percentual) fica em <b>folds puros</b>
 * ({@link #allocHotspots}, {@link #lockContention}, {@link #gcActivity}), testaveis
 * sem depender da amostragem do JFR no ambiente.
 */
public final class EvidenceExtractor {

    static final int TOP_N = 5;

    private EvidenceExtractor() {
    }

    /** Sinais headline + agregados por dimensao, extraidos da mesma passada. */
    public record Result(TriageSignals signals, Dimensions dimensions) {
    }

    /** Uma amostra de alocacao crua: call site e bytes. */
    public record AllocSample(String site, long bytes) {
    }

    /** Um evento de espera em monitor cru: call site, monitor e tempo. */
    public record LockSample(String site, String monitorClass, long waitMs) {
    }

    /**
     * Le o snapshot uma vez e devolve sinais + dimensoes. A janela e a do PROPRIO
     * request ({@code [capturedAt - durationMs, capturedAt]}) — nao um intervalo fixo
     * — para a evidencia refletir o que aconteceu durante a requisicao, sem varrer a
     * vida ociosa da thread (ex.: park no pool entre requests). {@code threadName} e a
     * thread que atendeu o request, usada para atribuir a atividade de thread.
     */
    public static Result extract(Path jfr, Instant capturedAt, long durationMs, String threadName)
            throws IOException {
        Acc acc = new Acc(threadName);
        Instant from = capturedAt.minusMillis(Math.max(0, durationMs));
        JfrSnapshot.forEachEvent(jfr, from, capturedAt, acc::accept);
        return acc.toResult();
    }

    // --- folds puros (sem JFR), testaveis isoladamente ---

    /** Agrega as pausas de GC: count, pausa total e a lista. Null se vazio. */
    public static GcActivity gcActivity(List<GcActivity.Pause> pauses) {
        if (pauses.isEmpty()) {
            return null;
        }
        long total = pauses.stream().mapToLong(GcActivity.Pause::sumPausesMs).sum();
        return new GcActivity(pauses.size(), total, List.copyOf(pauses));
    }

    /** Soma por site, ranqueia por bytes e calcula o percentual. Null se vazio. */
    public static AllocationHotspots allocHotspots(List<AllocSample> samples, int topN) {
        if (samples.isEmpty()) {
            return null;
        }
        Map<String, Long> bySite = new LinkedHashMap<>();
        for (AllocSample s : samples) {
            bySite.merge(s.site(), s.bytes(), Long::sum);
        }
        long total = bySite.values().stream().mapToLong(Long::longValue).sum();
        List<AllocationHotspots.Site> top = bySite.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(topN)
                .map(e -> new AllocationHotspots.Site(e.getKey(), e.getValue(), round1(e.getValue() * 100.0 / total)))
                .toList();
        return new AllocationHotspots(total, samples.size(), top);
    }

    /** Soma a espera por site e ranqueia. Null se vazio. */
    public static LockContention lockContention(List<LockSample> samples, int topN) {
        if (samples.isEmpty()) {
            return null;
        }
        Map<String, Long> waitBySite = new LinkedHashMap<>();
        Map<String, String> monitorBySite = new HashMap<>();
        for (LockSample s : samples) {
            waitBySite.merge(s.site(), s.waitMs(), Long::sum);
            monitorBySite.putIfAbsent(s.site(), s.monitorClass());
        }
        long total = waitBySite.values().stream().mapToLong(Long::longValue).sum();
        List<LockContention.Site> top = waitBySite.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(topN)
                .map(e -> new LockContention.Site(e.getKey(), monitorBySite.get(e.getKey()), e.getValue()))
                .toList();
        return new LockContention(total, samples.size(), top);
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** Acumulador mutavel de uma passada: coleta os eventos crus por dimensao. */
    private static final class Acc {
        private final List<GcActivity.Pause> pauses = new ArrayList<>();
        private long longestGcMs;
        private final List<AllocSample> allocs = new ArrayList<>();
        private final List<LockSample> locks = new ArrayList<>();
        private long totalLockWaitMs; // JVM-wide, com ou sem site (sinal da triagem)

        private final String targetThread;
        private long sleepMs;
        private String sleepSite;
        private long ioMs;
        private long threadLockMs;
        private long parkMs;
        private int cpuSamples;
        private boolean sawTargetThread;

        private Acc(String targetThread) {
            this.targetThread = targetThread;
        }

        void accept(RecordedEvent e) {
            String type = e.getEventType().getName();
            switch (type) {
                case "jdk.GarbageCollection" -> gc(e);
                case "jdk.ObjectAllocationSample" -> alloc(e);
                case "jdk.JavaMonitorEnter" -> lock(e);
                default -> { /* dimensao JVM-wide irrelevante */ }
            }
            thread(e, type);
        }

        private void gc(RecordedEvent e) {
            long longest = JfrSnapshot.durationMillis(e, "longestPause");
            pauses.add(new GcActivity.Pause(
                    e.hasField("name") ? e.getString("name") : "GC",
                    e.hasField("cause") ? e.getString("cause") : "?",
                    longest, JfrSnapshot.durationMillis(e, "sumOfPauses")));
            longestGcMs = Math.max(longestGcMs, longest);
        }

        private void alloc(RecordedEvent e) {
            String site = JfrSnapshot.topFrame(e);
            long weight = e.hasField("weight") ? e.getLong("weight") : 0L;
            if (site != null && weight > 0) {
                allocs.add(new AllocSample(site, weight));
            }
        }

        private void lock(RecordedEvent e) {
            long waitMs = e.getDuration().toMillis();
            totalLockWaitMs += waitMs;
            String site = JfrSnapshot.topFrame(e);
            if (site == null) {
                return;
            }
            String monitor = "?";
            if (e.hasField("monitorClass")) {
                RecordedClass c = e.getClass("monitorClass");
                if (c != null) {
                    monitor = c.getName();
                }
            }
            locks.add(new LockSample(site, monitor, waitMs));
        }

        private void thread(RecordedEvent e, String type) {
            if (targetThread == null || !targetThread.equals(JfrSnapshot.threadName(e))) {
                return;
            }
            sawTargetThread = true;
            switch (type) {
                case "jdk.ThreadSleep" -> {
                    sleepMs += e.getDuration().toMillis();
                    if (sleepSite == null) {
                        sleepSite = JfrSnapshot.topFrame(e);
                    }
                }
                case "jdk.SocketRead", "jdk.SocketWrite", "jdk.FileRead", "jdk.FileWrite" ->
                        ioMs += e.getDuration().toMillis();
                case "jdk.JavaMonitorEnter" -> threadLockMs += e.getDuration().toMillis();
                case "jdk.ThreadPark" -> parkMs += e.getDuration().toMillis();
                case "jdk.ExecutionSample" -> cpuSamples++;
                default -> { /* irrelevante para a atividade da thread */ }
            }
        }

        Result toResult() {
            long totalGcMs = pauses.stream().mapToLong(GcActivity.Pause::sumPausesMs).sum();
            long totalAllocBytes = allocs.stream().mapToLong(AllocSample::bytes).sum();
            TriageSignals signals = new TriageSignals(
                    pauses.size(), longestGcMs, totalGcMs, totalLockWaitMs, totalAllocBytes);
            Dimensions dims = new Dimensions(
                    gcActivity(pauses),
                    allocHotspots(allocs, TOP_N),
                    lockContention(locks, TOP_N),
                    threadDim(),
                    null);
            return new Result(signals, dims);
        }

        private ThreadActivity threadDim() {
            if (targetThread == null || !sawTargetThread) {
                return null;
            }
            return new ThreadActivity(targetThread, sleepMs, sleepSite, ioMs, threadLockMs, parkMs, cpuSamples);
        }
    }
}

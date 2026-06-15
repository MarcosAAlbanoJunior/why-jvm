package io.whyjvm.capture;

/**
 * O que a thread do request fez na janela: espera vs trabalho (dimensao
 * {@code threadActivity}). Distingue lentidao por espera externa (sleep/I/O/lock)
 * de lentidao por trabalho na JVM (CPU). Null quando a thread nao foi registrada.
 */
public record ThreadActivity(
        String thread,
        long sleepMs,
        String sleepSite,
        long ioMs,
        long lockMs,
        long parkMs,
        int cpuSamples
) {
}

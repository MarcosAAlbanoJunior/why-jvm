package io.whyjvm.trigger;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Baseline movel de latencia por endpoint. Detecta lentidao anomala <b>sem
 * limiar global</b>: um endpoint naturalmente lento alertaria o tempo todo. Cada
 * endpoint tem seu p99 movel, e uma requisicao e anomala quando passa de
 * {@code factor} x p99.
 *
 * <p>"Nao comece complexo": p99 sobre uma janela deslizante de amostras recentes
 * por endpoint. EWMA ou desvio padrao podem substituir depois sem mexer no resto.
 *
 * <p>Thread-safe: a janela de cada endpoint sincroniza internamente; {@code onEnd}
 * e concorrente.
 *
 * <p>TODO Fase 5: estado por instancia; com varias JVMs, mover para Redis para um
 * baseline global por endpoint.
 */
public final class LatencyBaseline {

    /** Amostras recentes mantidas por endpoint. */
    private static final int WINDOW = 200;
    /** Antes deste numero de amostras, o baseline ainda nao e confiavel. */
    private static final int WARMUP = 50;
    /** Percentil usado como referencia de "normal". */
    private static final int PERCENTILE = 99;

    private final double factor;
    private final ConcurrentMap<String, Samples> byEndpoint = new ConcurrentHashMap<>();

    public LatencyBaseline(double factor) {
        this.factor = factor;
    }

    /**
     * Registra a amostra e diz se ela e anomala (lenta) frente ao baseline atual
     * do endpoint. Durante o warmup nunca acusa anomalia — sem historico nao da
     * para distinguir lento de normal.
     */
    public boolean isAnomalousAndRecord(String endpoint, long durationMs) {
        return byEndpoint.computeIfAbsent(endpoint, e -> new Samples()).offerAndCheck(durationMs, factor);
    }

    /** Janela deslizante por endpoint: ring buffer de duracoes, sincronizado. */
    private static final class Samples {
        private final long[] buffer = new long[WINDOW];
        private int count; // posicoes preenchidas (ate WINDOW)
        private int next;  // proxima posicao de escrita (circular)

        synchronized boolean offerAndCheck(long durationMs, double factor) {
            boolean anomalous = false;
            if (count >= WARMUP) {
                anomalous = durationMs > factor * percentile(PERCENTILE);
            }
            buffer[next] = durationMs;
            next = (next + 1) % WINDOW;
            if (count < WINDOW) {
                count++;
            }
            return anomalous;
        }

        private long percentile(int p) {
            long[] sorted = Arrays.copyOf(buffer, count);
            Arrays.sort(sorted);
            int idx = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
            idx = Math.max(0, Math.min(idx, sorted.length - 1));
            return sorted[idx];
        }
    }
}

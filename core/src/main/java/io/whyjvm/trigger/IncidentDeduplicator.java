package io.whyjvm.trigger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Portao 1, parte 2: o <b>controle de tempestade</b>.
 *
 * <p>Quando algo quebra, milhares de requisicoes falham. Disparar uma
 * investigacao por falha e um apocalipse de token. Este componente garante
 * <b>uma</b> investigacao por fingerprint a cada janela de cooldown; as demais
 * ocorrencias do mesmo fingerprint apenas incrementam um contador em memoria
 * (custo zero de token).
 *
 * <p>Thread-safe: {@code onEnd} e chamado concorrentemente por varias threads de
 * request. A decisao de disparar e a marcacao da janela acontecem atomicamente
 * por fingerprint via {@link ConcurrentMap#compute}.
 *
 * <p>TODO: quando escalar para varias JVMs, mover este estado para Redis
 * (o cooldown passa a ser global, nao por instancia).
 */
public final class IncidentDeduplicator {

    /** Acima deste numero de fingerprints distintos, varremos os expirados. */
    private static final int SWEEP_THRESHOLD = 1024;

    /** Janela em que um mesmo fingerprint nao dispara uma nova investigacao. */
    private final Duration cooldown;
    private final Clock clock;
    private final ConcurrentMap<String, Window> windows = new ConcurrentHashMap<>();

    public IncidentDeduplicator(Duration cooldown) {
        this(cooldown, Clock.systemUTC());
    }

    // Visivel para teste: injeta um relogio controlavel.
    IncidentDeduplicator(Duration cooldown, Clock clock) {
        this.cooldown = cooldown;
        this.clock = clock;
    }

    /**
     * @return {@code true} se este fingerprint deve disparar uma investigacao
     *         agora (primeira ocorrencia da janela); {@code false} se esta em
     *         cooldown — caso em que apenas incrementa o contador de ocorrencias
     *         suprimidas.
     */
    public boolean shouldFire(String fingerprint) {
        Instant now = clock.instant();
        boolean[] fire = {false};
        // A decisao e tomada DENTRO do compute para ser atomica por fingerprint:
        // duas threads concorrentes no mesmo fingerprint nunca disparam ambas.
        windows.compute(fingerprint, (fp, current) -> {
            if (current == null || current.isExpired(now, cooldown)) {
                fire[0] = true;
                return new Window(now);
            }
            current.suppressed.incrementAndGet();
            return current;
        });
        if (windows.size() > SWEEP_THRESHOLD) {
            windows.values().removeIf(w -> w.isExpired(now, cooldown));
        }
        return fire[0];
    }

    /** Quantas ocorrencias deste fingerprint foram suprimidas na janela atual. */
    public long suppressedCount(String fingerprint) {
        Window window = windows.get(fingerprint);
        return window == null ? 0L : window.suppressed.get();
    }

    private static final class Window {
        final Instant firstFiredAt;
        final AtomicLong suppressed = new AtomicLong();

        Window(Instant firstFiredAt) {
            this.firstFiredAt = firstFiredAt;
        }

        boolean isExpired(Instant now, Duration cooldown) {
            return now.isAfter(firstFiredAt.plus(cooldown));
        }
    }
}

package io.whyjvm.capture;

/**
 * Um span lento do trace e quanto dominou a latencia (dimensao {@code slowTraces}).
 *
 * <p>Montado por {@link SlowTraceAssembler} a partir dos spans do trace retidos
 * no momento do disparo do incidente.
 */
public record SlowTrace(
        String span,
        long selfMs,
        long totalMs
) {
}

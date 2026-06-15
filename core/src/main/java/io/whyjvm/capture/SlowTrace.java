package io.whyjvm.capture;

/**
 * Um span lento do trace e quanto dominou a latencia (dimensao {@code slowTraces}).
 *
 * <p>PENDENTE (Fase 3): a captura da arvore do trace ainda nao foi implementada;
 * por ora a dimensao vem null. O tipo ja existe para fechar o contrato.
 */
public record SlowTrace(
        String span,
        long selfMs,
        long totalMs
) {
}

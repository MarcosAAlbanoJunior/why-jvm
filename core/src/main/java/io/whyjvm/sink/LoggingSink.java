package io.whyjvm.sink;

import io.whyjvm.agent.Laudo;

import java.util.logging.Logger;

/**
 * Sink padrao: escreve o laudo no log. Util no v1 e como fallback. Sinks reais
 * (Slack, WhatsApp) implementam a mesma interface.
 */
public final class LoggingSink implements Sink {

    private static final Logger LOG = Logger.getLogger(LoggingSink.class.getName());

    @Override
    public void publish(Laudo laudo) {
        String codigo = laudo.codeContext() == null
                ? "(sem contexto de codigo)"
                : laudo.codeContext().render();
        LOG.info("""

                ===== LAUDO RCA =====
                Endpoint   : %s
                Tipo       : %s
                Causa      : %s
                Confianca  : %s
                Evidencia  : %s
                Descartadas: %s
                Correcao   : %s

                Codigo:
                %s
                =====================
                """.formatted(
                laudo.endpoint(),
                laudo.tipo(),
                laudo.causaRaiz(),
                laudo.confianca(),
                laudo.evidencia(),
                laudo.hipotesesDescartadas(),
                laudo.correcaoSugerida(),
                codigo
        ));
    }
}

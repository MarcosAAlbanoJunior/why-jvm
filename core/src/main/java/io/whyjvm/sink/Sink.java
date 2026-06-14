package io.whyjvm.sink;

import io.whyjvm.agent.Laudo;

/**
 * Fronteira de extensao da saida (ponto 3 do modo autonomo: onde as mensagens
 * sao postadas). Mesmo padrao plugavel da {@code LlmProvider}: a comunidade
 * adiciona um sink novo (Slack, WhatsApp/Evolution API, webhook) sem tocar no
 * nucleo.
 */
public interface Sink {

    void publish(Laudo laudo);
}

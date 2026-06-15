package io.whyjvm.sink.email;

import java.util.List;

/**
 * Configuracao SMTP do {@link EmailSink}. As credenciais sao passadas por quem
 * monta o sink (tipicamente lidas do ambiente) — padrao BYOK, o nucleo nunca
 * guarda segredo.
 *
 * @param startTls true para STARTTLS na porta 587 (Gmail, Outlook, etc.).
 */
public record SmtpConfig(
        String host,
        int port,
        String username,
        String password,
        String from,
        List<String> recipients,
        boolean startTls
) {
    public SmtpConfig {
        recipients = List.copyOf(recipients);
    }
}

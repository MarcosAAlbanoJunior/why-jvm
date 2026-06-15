package io.whyjvm.sink.email;

import io.whyjvm.agent.Laudo;
import io.whyjvm.sink.Sink;
import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * Sink que envia o laudo por e-mail (SMTP). Funciona com qualquer servidor —
 * Gmail, Outlook, SendGrid, Mailgun — bastando host/porta/credenciais.
 *
 * <p>A renderizacao de assunto e corpo e separada do envio, para ser testavel
 * sem rede. O envio em si e fino: monta a mensagem e chama {@code Transport}.
 *
 * <p>Falha de envio nao derruba o investigador: e logada e o fluxo segue.
 */
public final class EmailSink implements Sink {

    private static final Logger LOG = Logger.getLogger(EmailSink.class.getName());

    private final SmtpConfig config;

    public EmailSink(SmtpConfig config) {
        this.config = config;
    }

    @Override
    public void publish(Laudo laudo) {
        try {
            MimeMessage message = new MimeMessage(newSession());
            message.setFrom(new InternetAddress(config.from()));
            for (String to : config.recipients()) {
                message.addRecipient(Message.RecipientType.TO, new InternetAddress(to));
            }
            message.setSubject(renderSubject(laudo));
            message.setText(renderBody(laudo), "UTF-8");
            Transport.send(message);
            LOG.info("Laudo de " + laudo.endpoint() + " enviado por e-mail para " + config.recipients());
        } catch (MessagingException e) {
            LOG.log(Level.SEVERE, "Falha ao enviar o laudo por e-mail", e);
        }
    }

    private Session newSession() {
        Properties props = new Properties();
        props.put("mail.smtp.host", config.host());
        props.put("mail.smtp.port", String.valueOf(config.port()));
        props.put("mail.smtp.auth", "true");
        if (config.startTls()) {
            props.put("mail.smtp.starttls.enable", "true");
        }
        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(config.username(), config.password());
            }
        });
    }

    static String renderSubject(Laudo l) {
        return "[why-jvm] RCA %s em %s (confianca %s)".formatted(l.tipo(), l.endpoint(), l.confianca());
    }

    static String renderBody(Laudo l) {
        String evidencia = l.evidencia().isEmpty()
                ? "(sem evidencia listada)"
                : l.evidencia().stream().map(e -> "  - " + e).collect(Collectors.joining("\n"));
        return """
                Incidente em %s (%s)

                Causa raiz: %s
                Confianca:  %s

                Evidencia:
                %s

                Correcao sugerida:
                %s

                --
                why-jvm — RCA acionado por gatilho
                """.formatted(
                l.endpoint(), l.tipo(), l.causaRaiz(), l.confianca(), evidencia, l.correcaoSugerida());
    }
}

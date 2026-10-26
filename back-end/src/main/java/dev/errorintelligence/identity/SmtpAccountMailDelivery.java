package dev.errorintelligence.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.auth.mail.mode", havingValue = "smtp", matchIfMissing = true)
final class SmtpAccountMailDelivery implements AccountMailDelivery {
  private final JavaMailSender sender;
  private final String from;

  SmtpAccountMailDelivery(
      JavaMailSender sender,
      @Value("${app.auth.mail.from:}") String from,
      @Value("${spring.mail.host:}") String host) {
    if (from.isBlank()) throw new IllegalStateException("MAIL_FROM is required for SMTP delivery.");
    if (host.isBlank()) throw new IllegalStateException("SMTP_HOST is required for SMTP delivery.");
    this.sender = sender;
    this.from = from;
  }

  @Override
  public void send(String recipient, String subject, String body) {
    var message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(recipient);
    message.setSubject(subject);
    message.setText(body);
    sender.send(message);
  }
}

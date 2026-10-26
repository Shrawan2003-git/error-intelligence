package dev.errorintelligence.identity;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AuthMailOutbox {
  private static final Logger LOG = LoggerFactory.getLogger(AuthMailOutbox.class);
  private final JdbcTemplate jdbc;
  private final AuthMailCipher cipher;
  private final AccountMailDelivery delivery;

  AuthMailOutbox(JdbcTemplate jdbc, AuthMailCipher cipher, AccountMailDelivery delivery) {
    this.jdbc = jdbc;
    this.cipher = cipher;
    this.delivery = delivery;
  }

  void queue(UUID challengeId, String recipient, String subject, String body) {
    UUID id = UUID.randomUUID();
    AuthMailCipher.Sealed sealed = cipher.seal(id, body);
    jdbc.update(
        "insert into intelligence.auth_mail_outbox(id,challenge_id,recipient,subject,nonce,ciphertext) values(?,?,?,?,?,?)",
        id,
        challengeId,
        recipient,
        subject,
        sealed.nonce(),
        sealed.ciphertext());
  }

  @Scheduled(initialDelayString = "PT1S", fixedDelayString = "${app.auth.mail.poll-interval:PT2S}")
  @Transactional
  public void deliverPending() {
    jdbc.update(
        "delete from intelligence.auth_mail_outbox o where not exists"
            + " (select 1 from intelligence.auth_challenges c where c.id=o.challenge_id"
            + " and c.consumed_at is null and c.expires_at>now())");
    var pending =
        jdbc.query(
            "select o.id,o.recipient,o.subject,o.nonce,o.ciphertext,o.attempts"
                + " from intelligence.auth_mail_outbox o"
                + " where o.next_attempt_at<=now() order by o.created_at,o.id"
                + " limit 10 for update of o skip locked",
            (rs, row) ->
                new Pending(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getBytes(4),
                    rs.getBytes(5),
                    rs.getInt(6)));
    for (Pending mail : pending) {
      try {
        delivery.send(
            mail.recipient(),
            mail.subject(),
            cipher.open(mail.id(), mail.nonce(), mail.ciphertext()));
        jdbc.update("delete from intelligence.auth_mail_outbox where id=?", mail.id());
      } catch (RuntimeException error) {
        long seconds = Math.min(3600, 5L << Math.min(mail.attempts(), 10));
        jdbc.update(
            "update intelligence.auth_mail_outbox set attempts=attempts+1,next_attempt_at=? where id=?",
            Timestamp.from(Instant.now().plusSeconds(seconds)),
            mail.id());
        LOG.warn(
            "Account mail delivery failed for message {} ({}); retry scheduled.",
            mail.id(),
            error.getClass().getSimpleName());
      }
    }
    jdbc.update(
        "delete from intelligence.auth_challenges where"
            + " (consumed_at is not null and consumed_at<now()-interval '1 day')"
            + " or expires_at<now()-interval '1 day'");
  }

  private record Pending(
      UUID id, String recipient, String subject, byte[] nonce, byte[] ciphertext, int attempts) {}
}

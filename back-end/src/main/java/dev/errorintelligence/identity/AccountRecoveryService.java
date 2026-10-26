package dev.errorintelligence.identity;

import dev.errorintelligence.common.api.DomainException;
import dev.errorintelligence.common.security.Credentials;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountRecoveryService {
  private final UserRepository users;
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwords;
  private final AuthMailOutbox mail;
  private final AuditWriter audit;
  private final String publicUrl;

  AccountRecoveryService(
      UserRepository users,
      JdbcTemplate jdbc,
      PasswordEncoder passwords,
      AuthMailOutbox mail,
      AuditWriter audit,
      Environment environment,
      @Value("${app.auth.public-url:}") String publicUrl) {
    this.users = users;
    this.jdbc = jdbc;
    this.passwords = passwords;
    this.mail = mail;
    this.audit = audit;
    URI uri;
    try {
      uri = URI.create(publicUrl);
    } catch (IllegalArgumentException error) {
      throw new IllegalStateException("PUBLIC_APP_URL must be a valid application URL.", error);
    }
    boolean localMail =
        environment.acceptsProfiles(Profiles.of("native"))
            || environment.getProperty("app.auth.mail.allow-file", Boolean.class, false);
    boolean nativeLoopback =
        localMail
            && "http".equals(uri.getScheme())
            && ("127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost()));
    if (uri.getHost() == null
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null
        || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))
        || (!"https".equals(uri.getScheme()) && !nativeLoopback))
      throw new IllegalStateException(
          "PUBLIC_APP_URL must be an HTTPS origin (or local HTTP in the native profile).");
    this.publicUrl = publicUrl.replaceAll("/+$", "");
  }

  @Transactional
  public void issueVerification(UserAccount user) {
    if (user.emailVerifiedAt() != null) return;
    String token = issue(user, "VERIFY_EMAIL", 30);
    String link = publicUrl + "/verify-email#token=" + token;
    mail.queue(
        challengeId(token),
        user.email(),
        "Verify your Error Intelligence email",
        "Verify your email to activate your workspace:\n\n"
            + link
            + "\n\nThis link expires in 30 minutes. If you did not create an account, ignore this email.");
  }

  @Transactional
  public void resendVerification(String email) {
    users
        .findByEmail(IdentityService.normalizeEmail(email))
        .filter(user -> user.emailVerifiedAt() == null)
        .ifPresent(this::issueVerification);
  }

  @Transactional
  public void verifyEmail(String token) {
    UserAccount user = consume(token, "VERIFY_EMAIL");
    user.verifyEmail(Instant.now());
    audit.record(firstOrganization(user.id()), user.id(), "EMAIL_VERIFIED", user.id());
  }

  @Transactional
  public void requestPasswordReset(String email) {
    users
        .findByEmail(IdentityService.normalizeEmail(email))
        .filter(user -> user.emailVerifiedAt() != null)
        .ifPresent(
            user -> {
              String token = issue(user, "RESET_PASSWORD", 15);
              String link = publicUrl + "/reset-password#token=" + token;
              mail.queue(
                  challengeId(token),
                  user.email(),
                  "Reset your Error Intelligence password",
                  "Use this link to choose a new password:\n\n"
                      + link
                      + "\n\nThis link expires in 15 minutes and can be used once."
                      + " If you did not request this, ignore this email.");
            });
  }

  @Transactional
  public void resetPassword(String token, String newPassword) {
    validatePassword(newPassword);
    UserAccount user = consume(token, "RESET_PASSWORD");
    user.changePasswordHash(passwords.encode(newPassword));
    jdbc.update(
        "update intelligence.sessions set revoked_at=now() where user_id=? and revoked_at is null",
        user.id());
    jdbc.update(
        "update intelligence.auth_challenges set consumed_at=now()"
            + " where user_id=? and consumed_at is null",
        user.id());
    audit.record(firstOrganization(user.id()), user.id(), "PASSWORD_RESET", user.id());
  }

  static void validatePassword(String password) {
    if (password == null
        || password.length() < 12
        || password.length() > 64
        || password.getBytes(StandardCharsets.UTF_8).length > 72)
      throw new DomainException(
          HttpStatus.BAD_REQUEST,
          "INVALID_PASSWORD",
          "Password must be 12–64 characters and at most 72 UTF-8 bytes.");
  }

  private String issue(UserAccount user, String purpose, int minutes) {
    jdbc.update(
        "update intelligence.auth_challenges set consumed_at=now()"
            + " where user_id=? and purpose=? and consumed_at is null",
        user.id(),
        purpose);
    String token = Credentials.randomToken();
    jdbc.update(
        "insert into intelligence.auth_challenges(id,user_id,purpose,digest,expires_at)"
            + " values(?,?,?,?,?)",
        UUID.randomUUID(),
        user.id(),
        purpose,
        Credentials.digest(token),
        Timestamp.from(Instant.now().plus(minutes, ChronoUnit.MINUTES)));
    return token;
  }

  private UUID challengeId(String token) {
    return jdbc.queryForObject(
        "select id from intelligence.auth_challenges where digest=?",
        UUID.class,
        Credentials.digest(token));
  }

  private UserAccount consume(String token, String purpose) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalidToken();
    UUID userId =
        jdbc
            .query(
                "select user_id from intelligence.auth_challenges"
                    + " where digest=? and purpose=? and consumed_at is null"
                    + " and expires_at>now() for update",
                (rs, row) -> rs.getObject(1, UUID.class),
                Credentials.digest(token),
                purpose)
            .stream()
            .findFirst()
            .orElseThrow(AccountRecoveryService::invalidToken);
    jdbc.update(
        "update intelligence.auth_challenges set consumed_at=now() where digest=?",
        Credentials.digest(token));
    return users.findById(userId).orElseThrow(AccountRecoveryService::invalidToken);
  }

  private UUID firstOrganization(UUID user) {
    return jdbc.queryForObject(
        "select organization_id from intelligence.memberships where user_id=?"
            + " order by created_at,organization_id limit 1",
        UUID.class,
        user);
  }

  private static DomainException invalidToken() {
    return new DomainException(
        HttpStatus.BAD_REQUEST,
        "INVALID_TOKEN",
        "This link is invalid or has expired. Request a new link.");
  }
}

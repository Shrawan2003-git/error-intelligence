package dev.errorintelligence;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.errorintelligence.common.security.Credentials;
import dev.errorintelligence.identity.AuthMailOutbox;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

final class TestAuthMail {
  private TestAuthMail() {}

  static String token(JdbcTemplate jdbc, AuthMailOutbox outbox, String email, String purpose)
      throws Exception {
    Path directory = Path.of(System.getProperty("app.auth.mail.outbox", "target/test-mail"));
    Pattern pattern = Pattern.compile("#token=([A-Za-z0-9_-]{43})");
    for (int attempt = 0; attempt < 50; attempt++) {
      outbox.deliverPending();
      if (Files.isDirectory(directory)) {
        try (var files = Files.list(directory)) {
          for (Path path : files.toList()) {
            String contents = Files.readString(path);
            if (!contents.startsWith("To: " + email + "\n")) continue;
            var match = pattern.matcher(contents);
            if (!match.find()) continue;
            String candidate = match.group(1);
            Integer active =
                jdbc.queryForObject(
                    "select count(*) from intelligence.auth_challenges c"
                        + " join intelligence.users u on u.id=c.user_id"
                        + " where u.email=? and c.purpose=? and c.digest=? and c.consumed_at is null",
                    Integer.class,
                    email,
                    purpose,
                    Credentials.digest(candidate));
            if (active != null && active == 1) return candidate;
          }
        }
      }
      Thread.sleep(100);
    }
    throw new AssertionError("No active test email for " + purpose + ".");
  }

  static void verify(MockMvc mvc, JdbcTemplate jdbc, AuthMailOutbox outbox, String email)
      throws Exception {
    String token = token(jdbc, outbox, email, "VERIFY_EMAIL");
    mvc.perform(
            post("/api/v1/auth/verification/confirm")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isNoContent());
  }
}

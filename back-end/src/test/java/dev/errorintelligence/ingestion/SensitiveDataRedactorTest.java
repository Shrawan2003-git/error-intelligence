package dev.errorintelligence.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.errorintelligence.ingestion.IngestionDtos.RequestContext;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SensitiveDataRedactorTest {
  private final SensitiveDataRedactor redactor = new SensitiveDataRedactor();

  @Test
  void redactsKnownCredentialsAndEmailFromFreeText() {
    String output =
        redactor.text(
            "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJhYmNkZWZnaGlqa2xtbm9wIn0.signature "
                + "github=ghp_1234567890abcdefghijklmnopqrstuv email dev@example.test");

    assertThat(output)
        .contains("Bearer [REDACTED]", "[REDACTED_EMAIL]")
        .doesNotContain("ghp_1234567890abcdefghijklmnopqrstuv", "dev@example.test", "signature");
  }

  @Test
  void excludesCredentialHeadersAndRedactsSensitiveQueryAndEnvironmentValues() {
    RequestContext request =
        new RequestContext(
            "get",
            "/users?access_token=server-secret&view=compact",
            Map.of(
                "Authorization", "Bearer raw-secret",
                "Cookie", "session=raw-secret",
                "User-Agent", "test-client"),
            Map.of("password", "raw-secret", "page", "2"),
            "{\"client_secret\":\"raw-secret\",\"state\":\"failed\"}");

    var output = redactor.request(request);
    var environment =
        redactor.environment(
            Map.of(
                "DB_PASSWORD", "raw-secret",
                "REGION", "us-east-1",
                "SUPPORT_EMAIL", "owner@example.test"));

    assertThat(output.get("path").toString())
        .contains("access_token=[REDACTED]")
        .doesNotContain("server-secret");
    assertThat(output.get("headers"))
        .asString()
        .contains("test-client")
        .doesNotContain("raw-secret");
    assertThat(output.get("query"))
        .asString()
        .contains("[REDACTED]", "2")
        .doesNotContain("raw-secret");
    assertThat(output.get("bodyExcerpt").toString()).doesNotContain("raw-secret");
    assertThat(environment.toString())
        .contains("us-east-1", "[REDACTED]")
        .doesNotContain("raw-secret", "owner@example.test");
  }
}

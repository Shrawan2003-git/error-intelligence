package dev.errorintelligence.ai;

import static org.assertj.core.api.Assertions.assertThat;

import dev.errorintelligence.ai.DiagnosticDtos.Category;
import dev.errorintelligence.ai.DiagnosticDtos.DiagnosticStep;
import dev.errorintelligence.ai.DiagnosticDtos.Evidence;
import dev.errorintelligence.ai.DiagnosticDtos.Hypothesis;
import dev.errorintelligence.ai.DiagnosticDtos.Result;
import dev.errorintelligence.ai.DiagnosticDtos.RiskLevel;
import dev.errorintelligence.ingestion.SensitiveDataRedactor;
import java.util.List;
import org.junit.jupiter.api.Test;

class DiagnosticOutputSanitizerTest {
  @Test
  void removesSecretsFromEveryModelControlledString() {
    var sanitizer = new DiagnosticOutputSanitizer(new SensitiveDataRedactor());
    String secret = "sk-proj-abcdefghijklmnopqrstuvwxyz0123456789ABCDE";
    Result input =
        new Result(
            Category.UNKNOWN,
            0.2,
            "Observed key " + secret,
            List.of(new Evidence("message", "key=" + secret)),
            List.of(new Hypothesis("token " + secret, "Found " + secret, 0.2)),
            List.of(new DiagnosticStep(1, "Review " + secret, "Expected " + secret, RiskLevel.LOW)),
            List.of(),
            List.of("Context " + secret));

    Result safe = sanitizer.sanitize(input);

    assertThat(safe.summary()).doesNotContain(secret);
    assertThat(safe.evidence().getFirst().excerpt()).doesNotContain(secret);
    assertThat(safe.hypotheses().getFirst().cause()).doesNotContain(secret);
    assertThat(safe.hypotheses().getFirst().rationale()).doesNotContain(secret);
    assertThat(safe.diagnosticSteps().getFirst().action()).doesNotContain(secret);
    assertThat(safe.diagnosticSteps().getFirst().expectedSignal()).doesNotContain(secret);
    assertThat(safe.limitations().getFirst()).doesNotContain(secret);
  }
}

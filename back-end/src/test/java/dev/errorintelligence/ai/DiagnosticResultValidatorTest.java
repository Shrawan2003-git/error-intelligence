package dev.errorintelligence.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.errorintelligence.ai.DiagnosticDtos.Category;
import dev.errorintelligence.ai.DiagnosticDtos.DiagnosticStep;
import dev.errorintelligence.ai.DiagnosticDtos.Evidence;
import dev.errorintelligence.ai.DiagnosticDtos.Hypothesis;
import dev.errorintelligence.ai.DiagnosticDtos.Result;
import dev.errorintelligence.ai.DiagnosticDtos.RiskLevel;
import dev.errorintelligence.similarity.AnalysisEvidence.AnalysisInput;
import dev.errorintelligence.similarity.AnalysisEvidence.EventEvidence;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DiagnosticResultValidatorTest {
  private final DiagnosticResultValidator validator = new DiagnosticResultValidator();

  @Test
  void acceptsEvidenceBoundedStructuredResult() {
    AnalysisInput input = input();
    Result result = result("Connection refused", "Check the service health endpoint.");

    assertThat(validator.validate(result, input)).isEqualTo(result);
  }

  @Test
  void rejectsEvidenceNotPresentInSanitizedEvent() {
    assertThatThrownBy(
            () ->
                validator.validate(
                    result("the database password is hunter2", "Check service health."), input()))
        .isInstanceOf(DiagnosticProviderException.class)
        .hasMessage("INVALID_DIAGNOSTIC_EVIDENCE");
  }

  @Test
  void rejectsDestructiveOrUnboundedRecommendations() {
    assertThatThrownBy(
            () ->
                validator.validate(
                    result("Connection refused", "Run DROP TABLE payments;"), input()))
        .isInstanceOf(DiagnosticProviderException.class)
        .hasMessage("UNSAFE_OR_INVALID_DIAGNOSTIC_STEP");
  }

  private AnalysisInput input() {
    return new AnalysisInput(
        "Payment failure",
        List.of(
            new EventEvidence(
                UUID.randomUUID(),
                "java.net.ConnectException",
                "Connection refused",
                "java.net.ConnectException: Connection refused",
                "/payments")),
        List.of());
  }

  private Result result(String excerpt, String action) {
    return new Result(
        Category.NETWORK,
        0.7,
        "The event may indicate a connection issue.",
        List.of(new Evidence("message", excerpt)),
        List.of(new Hypothesis("Service unavailable", "The connection was rejected.", 0.7)),
        List.of(
            new DiagnosticStep(1, action, "A health status or connection result.", RiskLevel.LOW)),
        List.of(),
        List.of("The available evidence is limited."));
  }
}

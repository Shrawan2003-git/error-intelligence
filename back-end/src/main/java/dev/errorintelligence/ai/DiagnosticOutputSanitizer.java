package dev.errorintelligence.ai;

import dev.errorintelligence.ai.DiagnosticDtos.DiagnosticStep;
import dev.errorintelligence.ai.DiagnosticDtos.Evidence;
import dev.errorintelligence.ai.DiagnosticDtos.Hypothesis;
import dev.errorintelligence.ai.DiagnosticDtos.Result;
import dev.errorintelligence.ingestion.SensitiveDataRedactor;
import org.springframework.stereotype.Component;

@Component
public class DiagnosticOutputSanitizer {
  private final SensitiveDataRedactor redactor;

  public DiagnosticOutputSanitizer(SensitiveDataRedactor redactor) {
    this.redactor = redactor;
  }

  public Result sanitize(Result result) {
    if (result == null) return null;
    return new Result(
        result.category(),
        result.confidence(),
        text(result.summary()),
        result.evidence() == null ? null : result.evidence().stream().map(this::evidence).toList(),
        result.hypotheses() == null
            ? null
            : result.hypotheses().stream().map(this::hypothesis).toList(),
        result.diagnosticSteps() == null
            ? null
            : result.diagnosticSteps().stream().map(this::step).toList(),
        result.relatedResolutionIds(),
        result.limitations() == null
            ? null
            : result.limitations().stream().map(this::text).toList());
  }

  private Evidence evidence(Evidence value) {
    return value == null ? null : new Evidence(text(value.field()), text(value.excerpt()));
  }

  private Hypothesis hypothesis(Hypothesis value) {
    return value == null
        ? null
        : new Hypothesis(text(value.cause()), text(value.rationale()), value.confidence());
  }

  private DiagnosticStep step(DiagnosticStep value) {
    return value == null
        ? null
        : new DiagnosticStep(
            value.order(), text(value.action()), text(value.expectedSignal()), value.riskLevel());
  }

  private String text(String value) {
    return value == null ? null : redactor.text(value);
  }
}

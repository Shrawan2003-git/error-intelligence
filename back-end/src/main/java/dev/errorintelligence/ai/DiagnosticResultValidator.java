package dev.errorintelligence.ai;

import dev.errorintelligence.ai.DiagnosticDtos.DiagnosticStep;
import dev.errorintelligence.ai.DiagnosticDtos.Evidence;
import dev.errorintelligence.ai.DiagnosticDtos.Hypothesis;
import dev.errorintelligence.ai.DiagnosticDtos.Result;
import dev.errorintelligence.similarity.AnalysisEvidence.AnalysisInput;
import dev.errorintelligence.similarity.AnalysisEvidence.EventEvidence;
import dev.errorintelligence.similarity.AnalysisEvidence.ResolutionEvidence;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class DiagnosticResultValidator {
  private static final Pattern DESTRUCTIVE =
      Pattern.compile(
          "(?i)(\\b(drop\\s+table|delete\\s+from|truncate\\s+table|rm\\s+-|kubectl\\s+delete|terraform\\s+destroy|shutdown|kill\\s+-9|curl\\s+https?://|wget\\s+https?://)\\b|```)");

  public Result validate(Result result, AnalysisInput input) {
    if (result == null
        || result.category() == null
        || !between(result.confidence())
        || blankOrLong(result.summary(), 2000)
        || result.evidence() == null
        || result.evidence().isEmpty()
        || result.evidence().size() > 8
        || result.hypotheses() == null
        || result.hypotheses().size() > 6
        || result.diagnosticSteps() == null
        || result.diagnosticSteps().isEmpty()
        || result.diagnosticSteps().size() > 10
        || result.relatedResolutionIds() == null
        || result.limitations() == null
        || result.limitations().size() > 8) {
      throw new DiagnosticProviderException("INVALID_DIAGNOSTIC_SCHEMA");
    }
    validateEvidence(result, input);
    validateHypotheses(result);
    validateSteps(result);
    Set<java.util.UUID> knownResolutions = new HashSet<>();
    for (ResolutionEvidence resolution : input.resolutions()) knownResolutions.add(resolution.id());
    if (!knownResolutions.containsAll(result.relatedResolutionIds()))
      throw new DiagnosticProviderException("INVALID_DIAGNOSTIC_REFERENCE");
    if (result.limitations().stream().anyMatch(value -> blankOrLong(value, 500)))
      throw new DiagnosticProviderException("INVALID_DIAGNOSTIC_SCHEMA");
    return result;
  }

  private void validateEvidence(Result result, AnalysisInput input) {
    for (Evidence evidence : result.evidence()) {
      if (evidence == null
          || evidence.field() == null
          || !Set.of("exceptionType", "message", "stackTrace", "route").contains(evidence.field())
          || blankOrLong(evidence.excerpt(), 500)) {
        throw new DiagnosticProviderException("INVALID_DIAGNOSTIC_EVIDENCE");
      }
      boolean matches =
          input.events().stream()
              .anyMatch(
                  event ->
                      evidence.excerpt().equals(field(event, evidence.field()))
                          || field(event, evidence.field()).contains(evidence.excerpt()));
      if (!matches) throw new DiagnosticProviderException("INVALID_DIAGNOSTIC_EVIDENCE");
    }
  }

  private String field(EventEvidence event, String name) {
    return switch (name) {
      case "exceptionType" -> event.exceptionType();
      case "message" -> event.message();
      case "stackTrace" -> event.stackTrace();
      case "route" -> event.route();
      default -> "";
    };
  }

  private void validateHypotheses(Result result) {
    for (Hypothesis hypothesis : result.hypotheses()) {
      if (hypothesis == null
          || blankOrLong(hypothesis.cause(), 500)
          || blankOrLong(hypothesis.rationale(), 1000)
          || !between(hypothesis.confidence())) {
        throw new DiagnosticProviderException("INVALID_DIAGNOSTIC_SCHEMA");
      }
    }
  }

  private void validateSteps(Result result) {
    int expectedOrder = 1;
    for (DiagnosticStep step : result.diagnosticSteps()) {
      if (step == null
          || step.order() != expectedOrder++
          || blankOrLong(step.action(), 700)
          || blankOrLong(step.expectedSignal(), 500)
          || step.riskLevel() == null
          || DESTRUCTIVE.matcher(step.action()).find()) {
        throw new DiagnosticProviderException("UNSAFE_OR_INVALID_DIAGNOSTIC_STEP");
      }
    }
  }

  private boolean between(double value) {
    return Double.isFinite(value) && value >= 0 && value <= 1;
  }

  private boolean blankOrLong(String value, int max) {
    return value == null || value.isBlank() || value.length() > max;
  }
}

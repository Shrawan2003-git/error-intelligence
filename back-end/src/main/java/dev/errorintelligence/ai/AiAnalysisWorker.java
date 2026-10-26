package dev.errorintelligence.ai;

import dev.errorintelligence.ai.DiagnosticDtos.DiagnosticStep;
import dev.errorintelligence.ai.DiagnosticDtos.Evidence;
import dev.errorintelligence.ai.DiagnosticDtos.Result;
import dev.errorintelligence.ai.DiagnosticDtos.RiskLevel;
import dev.errorintelligence.similarity.SimilarityService;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.ai.worker.enabled", havingValue = "true", matchIfMissing = true)
public class AiAnalysisWorker {
  private static final Logger log = LoggerFactory.getLogger(AiAnalysisWorker.class);

  private final AiAnalysisQueue queue;
  private final DiagnosticProvider provider;
  private final DiagnosticResultValidator validator;
  private final DiagnosticOutputSanitizer sanitizer;
  private final SimilarityService similarity;

  public AiAnalysisWorker(
      AiAnalysisQueue queue,
      DiagnosticProvider provider,
      DiagnosticResultValidator validator,
      DiagnosticOutputSanitizer sanitizer,
      SimilarityService similarity) {
    this.queue = queue;
    this.provider = provider;
    this.validator = validator;
    this.sanitizer = sanitizer;
    this.similarity = similarity;
  }

  @Scheduled(fixedDelayString = "${app.ai.poll-interval:PT1S}")
  public void poll() {
    AiAnalysisQueue.Claim claim;
    try {
      claim = queue.claimNext();
    } catch (RuntimeException exception) {
      log.warn("AI queue poll failed; failureType={}", exception.getClass().getSimpleName());
      return;
    }
    if (claim == null) return;
    UUID id = claim.id();
    dev.errorintelligence.similarity.AnalysisEvidence.AnalysisInput input;
    try {
      input = queue.input(id);
      input = similarity.enrichForAnalysis(id, input);
      DiagnosticProvider.ProviderResult answer = provider.analyze(input);
      Result safe = validator.validate(sanitizer.sanitize(answer.result()), input);
      queue.complete(
          claim,
          new DiagnosticProvider.ProviderResult(
              safe, answer.model(), answer.inputTokens(), answer.outputTokens()));
    } catch (DiagnosticProviderException exception) {
      fail(claim, exception.code(), inputIfAvailable(id));
    } catch (RuntimeException exception) {
      log.warn(
          "AI analysis failed; jobId={}, failureType={}", id, exception.getClass().getSimpleName());
      fail(claim, "ANALYSIS_FAILED", inputIfAvailable(id));
    }
  }

  private dev.errorintelligence.similarity.AnalysisEvidence.AnalysisInput inputIfAvailable(
      UUID id) {
    try {
      return queue.input(id);
    } catch (RuntimeException ignored) {
      return new dev.errorintelligence.similarity.AnalysisEvidence.AnalysisInput(
          "Unknown incident", List.of(), List.of());
    }
  }

  private void fail(
      AiAnalysisQueue.Claim claim,
      String code,
      dev.errorintelligence.similarity.AnalysisEvidence.AnalysisInput input) {
    queue.failed(claim, code, fallback(input));
  }

  private Result fallback(dev.errorintelligence.similarity.AnalysisEvidence.AnalysisInput input) {
    List<Evidence> evidence =
        input.events().stream()
            .findFirst()
            .map(
                event -> {
                  if (!event.message().isBlank())
                    return new Evidence("message", excerpt(event.message()));
                  if (!event.stackTrace().isBlank())
                    return new Evidence("stackTrace", excerpt(event.stackTrace()));
                  if (!event.route().isBlank())
                    return new Evidence("route", excerpt(event.route()));
                  return new Evidence("exceptionType", excerpt(event.exceptionType()));
                })
            .filter(item -> item.excerpt() != null && !item.excerpt().isBlank())
            .map(List::of)
            .orElse(List.of());
    return new Result(
        DiagnosticDtos.Category.UNKNOWN,
        0,
        "Automated diagnosis is unavailable. Use the sanitized event evidence to continue a manual investigation.",
        evidence,
        List.of(),
        List.of(
            new DiagnosticStep(
                1,
                "Review the exception type and top application stack frames.",
                "Identify the first application-owned frame and the failing dependency boundary.",
                RiskLevel.LOW),
            new DiagnosticStep(
                2,
                "Compare the affected release and environment with the latest verified resolution.",
                "Confirm whether the failure is limited to a deployment or environment change.",
                RiskLevel.LOW)),
        List.of(),
        List.of("AI provider output was unavailable or did not pass validation."));
  }

  private String excerpt(String value) {
    return value.length() <= 180 ? value : value.substring(0, 180);
  }
}

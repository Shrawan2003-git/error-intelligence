package dev.errorintelligence.ai;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DiagnosticDtos {
  private DiagnosticDtos() {}

  public enum Category {
    DATABASE,
    DI,
    AUTH,
    NETWORK,
    VALIDATION,
    APPLICATION,
    UNKNOWN
  }

  public enum RiskLevel {
    LOW,
    MEDIUM
  }

  public enum Status {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    RETRYING,
    FALLBACK
  }

  public record Evidence(String field, String excerpt) {}

  public record Hypothesis(String cause, String rationale, double confidence) {}

  public record DiagnosticStep(
      int order, String action, String expectedSignal, RiskLevel riskLevel) {}

  public record Result(
      Category category,
      double confidence,
      String summary,
      List<Evidence> evidence,
      List<Hypothesis> hypotheses,
      List<DiagnosticStep> diagnosticSteps,
      List<UUID> relatedResolutionIds,
      List<String> limitations) {}

  public record Accepted(UUID analysisId, Status status, Instant createdAt) {}

  public record Analysis(
      UUID id,
      UUID incidentId,
      Status status,
      String model,
      String promptVersion,
      Result result,
      String failureCode,
      int attemptCount,
      Integer inputTokens,
      Integer outputTokens,
      Instant createdAt,
      Instant updatedAt,
      Instant finishedAt) {}

  public record AnalyzeInput(@NotNull Boolean refresh) {}
}

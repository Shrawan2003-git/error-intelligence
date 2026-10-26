package dev.errorintelligence.similarity;

import java.util.List;
import java.util.UUID;

/** Public retrieval contract shared with diagnostic consumers. */
public final class AnalysisEvidence {
  private AnalysisEvidence() {}

  public record EventEvidence(
      UUID id, String exceptionType, String message, String stackTrace, String route) {}

  public record ResolutionEvidence(UUID id, String summary, String rootCause) {}

  public record AnalysisInput(
      String incidentTitle, List<EventEvidence> events, List<ResolutionEvidence> resolutions) {}
}

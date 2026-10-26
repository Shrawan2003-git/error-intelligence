package dev.errorintelligence.similarity;

import java.util.List;
import java.util.UUID;

public final class SimilarityDtos {
  private SimilarityDtos() {}

  public record Match(
      UUID sourceId,
      UUID incidentId,
      String sourceType,
      String title,
      String summary,
      String rootCause,
      double similarity) {}

  public record SearchResult(List<Match> items, String embeddingModel) {}
}

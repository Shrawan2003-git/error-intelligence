package dev.errorintelligence.ai;

import dev.errorintelligence.similarity.AnalysisEvidence.AnalysisInput;

public interface DiagnosticProvider {
  ProviderResult analyze(AnalysisInput input);

  record ProviderResult(
      DiagnosticDtos.Result result, String model, Integer inputTokens, Integer outputTokens) {}
}

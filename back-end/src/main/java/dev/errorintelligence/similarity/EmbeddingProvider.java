package dev.errorintelligence.similarity;

public interface EmbeddingProvider {
  float[] embed(String text);

  String model();
}

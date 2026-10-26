package dev.errorintelligence.similarity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

public final class OpenAiEmbeddingProvider implements EmbeddingProvider {
  private final RestClient client;
  private final String model;
  private final boolean configured;

  public OpenAiEmbeddingProvider(
      RestClient.Builder builder,
      String baseUrl,
      String apiKey,
      String model,
      JdkClientHttpRequestFactory factory) {
    this.model = model;
    this.configured = apiKey != null && !apiKey.isBlank();
    this.client =
        builder
            .baseUrl(baseUrl)
            .requestFactory(factory)
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .defaultHeader("Content-Type", "application/json")
            .build();
  }

  @Override
  public float[] embed(String text) {
    if (!configured) throw new IllegalStateException("Embedding provider is not configured.");
    if (text == null || text.isBlank() || text.length() > 12000)
      throw new IllegalArgumentException("Embedding input must be non-empty and bounded.");
    EmbeddingResponse response =
        client
            .post()
            .uri("/embeddings")
            .body(new EmbeddingRequest(model, text, 1536))
            .retrieve()
            .body(EmbeddingResponse.class);
    if (response == null || response.data() == null || response.data().size() != 1)
      throw new IllegalStateException("Embedding provider returned no vector.");
    List<Double> values = response.data().getFirst().embedding();
    if (values == null || values.size() != 1536)
      throw new IllegalStateException("Embedding provider returned an unexpected vector size.");
    float[] vector = new float[values.size()];
    for (int i = 0; i < vector.length; i++) {
      double value = values.get(i);
      if (!Double.isFinite(value)) throw new IllegalStateException("Embedding vector is invalid.");
      vector[i] = (float) value;
    }
    return vector;
  }

  @Override
  public String model() {
    return model;
  }

  private record EmbeddingRequest(String model, String input, int dimensions) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record EmbeddingResponse(List<EmbeddingData> data) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record EmbeddingData(List<Double> embedding) {}
}

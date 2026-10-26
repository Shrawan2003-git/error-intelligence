package dev.errorintelligence.similarity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class OpenAiEmbeddingProviderTest {
  @Test
  void sendsBoundedEmbeddingRequestAndValidatesDimensions() throws Exception {
    var requestBody = new AtomicReference<String>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        "/v1/embeddings",
        exchange -> {
          requestBody.set(new String(exchange.getRequestBody().readAllBytes()));
          byte[] response =
              new ObjectMapper()
                  .writeValueAsBytes(
                      Map.of("data", List.of(Map.of("embedding", Collections.nCopies(1536, 0.0)))));
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, response.length);
          exchange.getResponseBody().write(response);
          exchange.close();
        });
    server.start();
    try {
      var requestFactory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().build());
      var provider =
          new OpenAiEmbeddingProvider(
              RestClient.builder(),
              "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
              "test-key",
              "text-embedding-3-small",
              requestFactory);

      float[] vector = provider.embed("sanitized incident evidence");

      assertThat(vector).hasSize(1536);
      assertThat(provider.model()).isEqualTo("text-embedding-3-small");
      assertThat(requestBody.get())
          .contains("text-embedding-3-small")
          .contains("sanitized incident evidence")
          .contains("1536");
    } finally {
      server.stop(0);
    }
  }

  @Test
  void refusesToCallProviderWithoutConfiguredCredential() {
    var provider =
        new OpenAiEmbeddingProvider(
            RestClient.builder(),
            "https://api.openai.com/v1",
            "",
            "text-embedding-3-small",
            new JdkClientHttpRequestFactory(HttpClient.newBuilder().build()));

    assertThatThrownBy(() -> provider.embed("evidence"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Embedding provider is not configured.");
  }
}

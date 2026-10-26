package dev.errorintelligence.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.errorintelligence.similarity.EmbeddingProvider;
import dev.errorintelligence.similarity.OpenAiEmbeddingProvider;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
public class AiConfiguration {
  @Bean
  DiagnosticProvider diagnosticProvider(
      RestClient.Builder builder,
      ObjectMapper mapper,
      @Value("${app.ai.provider:openai}") String provider,
      @Value("${app.ai.openai.base-url:https://api.openai.com/v1}") String baseUrl,
      @Value("${OPENAI_API_KEY:}") String apiKey,
      @Value("${app.ai.openai.model:gpt-4.1-mini}") String model,
      @Value("${app.ai.timeout:PT12S}") Duration timeout) {
    if (!provider.equalsIgnoreCase("openai"))
      throw new IllegalStateException("Unsupported AI provider configuration.");
    JdkClientHttpRequestFactory requestFactory = requestFactory(timeout);
    RestClient.Builder bounded = builder.requestFactory(requestFactory);
    return new OpenAiDiagnosticProvider(bounded, mapper, baseUrl, apiKey, model);
  }

  @Bean
  EmbeddingProvider embeddingProvider(
      RestClient.Builder builder,
      @Value("${app.ai.openai.base-url:https://api.openai.com/v1}") String baseUrl,
      @Value("${OPENAI_API_KEY:}") String apiKey,
      @Value("${app.ai.embedding-model:text-embedding-3-small}") String model,
      @Value("${app.ai.timeout:PT12S}") Duration timeout) {
    return new OpenAiEmbeddingProvider(builder, baseUrl, apiKey, model, requestFactory(timeout));
  }

  private JdkClientHttpRequestFactory requestFactory(Duration timeout) {
    JdkClientHttpRequestFactory factory =
        new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
    factory.setReadTimeout(timeout);
    return factory;
  }
}

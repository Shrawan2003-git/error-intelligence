package dev.errorintelligence.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.errorintelligence.similarity.AnalysisEvidence.AnalysisInput;
import dev.errorintelligence.similarity.AnalysisEvidence.EventEvidence;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenAiDiagnosticProviderTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void sendsStrictEvidenceOnlyRequestAndParsesUsage() throws Exception {
    RestClient.Builder builder = RestClient.builder();
    MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    AnalysisInput input =
        new AnalysisInput(
            "Payment failed",
            List.of(
                new EventEvidence(
                    UUID.randomUUID(),
                    "ConnectException",
                    "Connection refused",
                    "ConnectException: Connection refused",
                    "/payments")),
            List.of());
    String diagnostic =
        "{\"category\":\"NETWORK\",\"confidence\":0.7,\"summary\":\"The connection was rejected.\","
            + "\"evidence\":[{\"field\":\"message\",\"excerpt\":\"Connection refused\"}],"
            + "\"hypotheses\":[{\"cause\":\"Service unavailable\",\"rationale\":\"The peer refused the connection.\",\"confidence\":0.7}],"
            + "\"diagnosticSteps\":[{\"order\":1,\"action\":\"Check the service health endpoint.\",\"expectedSignal\":\"Current service health.\",\"riskLevel\":\"LOW\"}],"
            + "\"relatedResolutionIds\":[],\"limitations\":[\"Only one event was supplied.\"]}";
    server
        .expect(requestTo("https://api.test/v1/responses"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer test-key"))
        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"store\":false"),
                        org.hamcrest.Matchers.containsString("\"strict\":true"),
                        org.hamcrest.Matchers.containsString("Connection refused"),
                        org.hamcrest.Matchers.not(
                            org.hamcrest.Matchers.containsString("\"tools\"")))))
        .andRespond(
            withSuccess(
                "{\"model\":\"test-model\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":"
                    + mapper.writeValueAsString(diagnostic)
                    + "}]}],\"usage\":{\"input_tokens\":31,\"output_tokens\":17}}",
                MediaType.APPLICATION_JSON));

    var provider =
        new OpenAiDiagnosticProvider(
            builder, mapper, "https://api.test/v1", "test-key", "test-model");
    var result = provider.analyze(input);

    assertThat(result.model()).isEqualTo("test-model");
    assertThat(result.inputTokens()).isEqualTo(31);
    assertThat(result.outputTokens()).isEqualTo(17);
    assertThat(result.result().evidence().getFirst().excerpt()).isEqualTo("Connection refused");
    server.verify();
  }
}

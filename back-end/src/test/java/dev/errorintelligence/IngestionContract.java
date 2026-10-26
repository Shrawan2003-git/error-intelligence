package dev.errorintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.errorintelligence.common.security.Credentials;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {"app.ai.worker.enabled=false", "app.ai.embedding-worker.enabled=false"})
abstract class IngestionContract {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired dev.errorintelligence.identity.AuthMailOutbox mail;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;

  @Test
  void keyAuthenticationRedactionIdempotencyAndRevocation() throws Exception {
    JsonNode session = mapper.readTree(register());
    String access = session.path("accessToken").asText();
    String projectId = createProject(access);
    var keyResponse =
        mvc.perform(
                post("/api/v1/projects/{id}/keys", projectId)
                    .with(csrf())
                    .header("Authorization", "Bearer " + access)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"label\":\"production SDK\"}"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andReturn();
    JsonNode keyPayload = mapper.readTree(keyResponse.getResponse().getContentAsString());
    String rawKey = keyPayload.path("secret").asText();
    String keyId = keyPayload.path("metadata").path("id").asText();
    assertThat(rawKey).startsWith("ei_").hasSize(46);
    assertThat(
            jdbc.queryForObject(
                "select digest from intelligence.ingestion_keys where id=?",
                String.class,
                UUID.fromString(keyId)))
        .isEqualTo(Credentials.digest(rawKey));

    UUID eventId = UUID.randomUUID();
    var first =
        mvc.perform(
                post("/ingest/v1/events")
                    .header("X-API-Key", rawKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(event(eventId, rawKey, "first failure")))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.duplicate").value(false))
            .andReturn();
    String persistedId =
        mapper.readTree(first.getResponse().getContentAsString()).path("eventId").asText();

    mvc.perform(
            post("/ingest/v1/events")
                .header("X-API-Key", rawKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(event(eventId, rawKey, "changed duplicate payload")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.duplicate").value(true))
        .andExpect(jsonPath("$.eventId").value(persistedId));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from intelligence.error_events where project_id=? and client_event_id=?",
                Integer.class,
                UUID.fromString(projectId),
                eventId))
        .isEqualTo(1);
    String stored =
        jdbc.queryForObject(
            "select exception_type || message || stack_trace || request_metadata::text || environment_metadata::text from intelligence.error_events where id=?",
            String.class,
            UUID.fromString(persistedId));
    assertThat(stored)
        .contains("first failure", "[REDACTED_EMAIL]", "[REDACTED]")
        .doesNotContain(
            rawKey, "owner@example.test", "Bearer", "Authorization", "Cookie", "db-password");

    jdbc.update(
        "update intelligence.error_events set occurred_at=now()-interval '3 days' where id=?",
        UUID.fromString(persistedId));
    var retention =
        new dev.errorintelligence.operations.EventRetentionJob(
            jdbc, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), 1);
    Integer purged =
        new org.springframework.transaction.support.TransactionTemplate(transactions)
            .execute(status -> retention.purgeExpiredBatch());
    assertThat(purged).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from intelligence.error_events where id=?",
                Integer.class,
                UUID.fromString(persistedId)))
        .isZero();
    mvc.perform(
            post("/ingest/v1/events")
                .header("X-API-Key", rawKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(event(eventId, rawKey, "replay after retention")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.duplicate").value(true))
        .andExpect(jsonPath("$.eventId").value(persistedId));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from intelligence.incident_occurrences where event_id=?",
                Integer.class,
                UUID.fromString(persistedId)))
        .isZero();

    mvc.perform(
            delete("/api/v1/projects/{projectId}/keys/{keyId}", projectId, keyId)
                .with(csrf())
                .header("Authorization", "Bearer " + access))
        .andExpect(status().isNoContent());
    mvc.perform(
            post("/ingest/v1/events")
                .header("X-API-Key", rawKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(event(UUID.randomUUID(), rawKey, "after revocation")))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INVALID_INGESTION_KEY"));
  }

  @Test
  void rejectsUnsupportedContentAndOversizedBodiesBeforeEventParsing() throws Exception {
    mvc.perform(post("/ingest/v1/events").contentType(MediaType.TEXT_PLAIN).content("event"))
        .andExpect(status().isUnsupportedMediaType())
        .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
    mvc.perform(
            post("/ingest/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("x".repeat(256 * 1024 + 1)))
        .andExpect(status().isPayloadTooLarge())
        .andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
  }

  private String register() throws Exception {
    mvc.perform(
            post("/api/v1/auth/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"owner@example.test\",\"password\":\"a-strong-password-123\",\"displayName\":\"Owner\",\"organizationName\":\"Platform\"}"))
        .andExpect(status().isAccepted());
    TestAuthMail.verify(mvc, jdbc, mail, "owner@example.test");
    return mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"owner@example.test\",\"password\":\"a-strong-password-123\"}"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private String createProject(String access) throws Exception {
    String response =
        mvc.perform(
                post("/api/v1/projects")
                    .with(csrf())
                    .header("Authorization", "Bearer " + access)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Payments\",\"slug\":\"payments\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return mapper.readTree(response).path("id").asText();
  }

  private String event(UUID eventId, String apiKey, String message) throws Exception {
    return mapper.writeValueAsString(
        Map.of(
            "eventId",
            eventId,
            "occurredAt",
            Instant.now().toString(),
            "environment",
            "production",
            "release",
            "release-42",
            "exceptionType",
            "com.example.PaymentException",
            "message",
            message + " for owner@example.test password=" + apiKey,
            "stackTrace",
            "at com.example.Payments.run(Payments.java:42) token=" + apiKey,
            "request",
            Map.of(
                "method",
                "POST",
                "path",
                "/payments?access_token=private-value",
                "headers",
                Map.of(
                    "Authorization",
                    "Bearer " + apiKey,
                    "Cookie",
                    "session=" + apiKey,
                    "User-Agent",
                    "integration-test"),
                "query",
                Map.of("password", "db-password", "region", "us-west-2"),
                "bodyExcerpt",
                "client_secret=private-value"),
            "environmentValues",
            Map.of("DB_PASSWORD", "db-password", "REGION", "us-west-2")));
  }
}

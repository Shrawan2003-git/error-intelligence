package dev.errorintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.errorintelligence.ai.AiAnalysisQueue;
import dev.errorintelligence.ai.DiagnosticDtos.Category;
import dev.errorintelligence.ai.DiagnosticDtos.DiagnosticStep;
import dev.errorintelligence.ai.DiagnosticDtos.Evidence;
import dev.errorintelligence.ai.DiagnosticDtos.Result;
import dev.errorintelligence.ai.DiagnosticDtos.RiskLevel;
import dev.errorintelligence.similarity.EmbeddingProvider;
import dev.errorintelligence.similarity.SimilarityService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "app.ai.worker.enabled=false",
      "app.ai.embedding-worker.enabled=false",
      "app.ai.daily-limit=1"
    })
abstract class IncidentContract {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired dev.errorintelligence.identity.AuthMailOutbox mail;
  @Autowired AiAnalysisQueue aiQueue;
  @Autowired dev.errorintelligence.similarity.EmbeddingJobQueue embeddingQueue;
  @Autowired dev.errorintelligence.similarity.EmbeddingBudget embeddingBudget;

  @Test
  void embeddingClaimsKeepSnapshotsAndRejectStaleWorkers() {
    jdbc.update("delete from intelligence.embedding_jobs");
    UUID org = UUID.randomUUID(), project = UUID.randomUUID(), incident = UUID.randomUUID();
    jdbc.update("insert into intelligence.organizations(id,name) values(?,'Queue contract')", org);
    jdbc.update(
        "insert into intelligence.projects(id,organization_id,name,slug) values(?,?,'Queue','queue')",
        project,
        org);
    jdbc.update(
        "insert into intelligence.incident_groups(id,organization_id,project_id,fingerprint_version,fingerprint,title,first_seen,last_seen) values(?,?,?,1,?,'Queue',now(),now())",
        incident,
        org,
        project,
        "f".repeat(64));
    embeddingQueue.enqueueIncident(org, project, incident, "original evidence");
    var first = embeddingQueue.claimNext();
    embeddingQueue.enqueueIncident(org, project, incident, "original evidence");
    assertThat(
            jdbc.queryForObject(
                "select status from intelligence.embedding_jobs where id=?",
                String.class,
                first.id()))
        .isEqualTo("RUNNING");
    embeddingQueue.enqueueIncident(org, project, incident, "changed evidence");
    float[] vector = new float[1536];
    vector[0] = 1;
    embeddingQueue.complete(first, vector, "text-embedding-3-small");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from intelligence.incident_embeddings where incident_group_id=?",
                Integer.class,
                incident))
        .isZero();
    var second = embeddingQueue.claimNext();
    assertThat(second.sourceText()).isEqualTo("changed evidence");
    jdbc.update(
        "update intelligence.embedding_jobs set updated_at=now()-interval '3 minutes' where id=?",
        second.id());
    var third = embeddingQueue.claimNext();
    embeddingQueue.complete(second, vector, "text-embedding-3-small");
    assertThat(
            jdbc.queryForObject(
                "select status from intelligence.embedding_jobs where id=?",
                String.class,
                third.id()))
        .isEqualTo("RUNNING");
    embeddingQueue.complete(third, vector, "text-embedding-3-small");
    embeddingQueue.enqueueIncident(org, project, incident, "changed evidence");
    assertThat(embeddingQueue.claimNext()).isNull();
    assertThat(
            jdbc.queryForObject(
                "select source_text from intelligence.incident_embeddings where incident_group_id=?",
                String.class,
                incident))
        .isEqualTo("changed evidence");
    embeddingQueue.enqueueIncident(org, project, incident, "abandoned evidence");
    for (int attempt = 0; attempt < 3; attempt++) {
      var claim = embeddingQueue.claimNext();
      assertThat(claim).isNotNull();
      jdbc.update(
          "update intelligence.embedding_jobs set updated_at=now()-interval '3 minutes' where id=?",
          claim.id());
    }
    assertThat(embeddingQueue.claimNext()).isNull();
    assertThat(
            jdbc.queryForObject(
                "select status from intelligence.embedding_jobs where id=?",
                String.class,
                first.id()))
        .isEqualTo("FAILED");

    jdbc.update("delete from intelligence.incident_ai_analyses");
    UUID analysis = UUID.randomUUID();
    jdbc.update(
        "insert into intelligence.incident_ai_analyses(id,organization_id,project_id,incident_group_id,status,model,prompt_version) values(?,?,?,?,'QUEUED','test','test')",
        analysis,
        org,
        project,
        incident);
    var diagnosticFirst = aiQueue.claimNext();
    jdbc.update(
        "update intelligence.incident_ai_analyses set updated_at=now()-interval '3 minutes' where id=?",
        analysis);
    var diagnosticSecond = aiQueue.claimNext();
    var fallback =
        new Result(
            Category.UNKNOWN,
            0,
            "Manual investigation required.",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());
    aiQueue.complete(
        diagnosticFirst,
        new dev.errorintelligence.ai.DiagnosticProvider.ProviderResult(fallback, "test", 0, 0));
    assertThat(
            jdbc.queryForObject(
                "select claim_token from intelligence.incident_ai_analyses where id=?",
                UUID.class,
                analysis))
        .isEqualTo(diagnosticSecond.token());
    jdbc.update(
        "update intelligence.incident_ai_analyses set updated_at=now()-interval '3 minutes' where id=?",
        analysis);
    assertThat(aiQueue.claimNext()).isNull();
    assertThat(
            jdbc.queryForObject(
                "select status from intelligence.incident_ai_analyses where id=?",
                String.class,
                analysis))
        .isEqualTo("FALLBACK");

    jdbc.update(
        "insert into intelligence.embedding_daily_usage(organization_id,usage_day,calls,input_characters) values(?,(now() at time zone 'UTC')::date,999,0)",
        org);
    embeddingBudget.reserve(org, "last permitted request");
    assertThatThrownBy(() -> embeddingBudget.reserve(org, "blocked request"))
        .isInstanceOf(dev.errorintelligence.common.api.DomainException.class);
    assertThat(
            jdbc.queryForObject(
                "select calls from intelligence.embedding_daily_usage where organization_id=?",
                Integer.class,
                org))
        .isEqualTo(1000);
  }

  @Autowired SimilarityService similarityService;
  @MockitoBean EmbeddingProvider embeddingProvider;

  @Test
  void dailyAiQuotaIsPerOrganizationAndDoesNotRejectAnActiveDuplicate() throws Exception {
    JsonNode owner = mapper.readTree(register("quota-owner@example.test", "Quota owner"));
    String access = owner.path("accessToken").asText();
    String project = createProject(access);
    String key = createKey(access, project);
    mvc.perform(
            post("/ingest/v1/events")
                .header("X-API-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(event(UUID.randomUUID())))
        .andExpect(status().isAccepted());
    mvc.perform(
            post("/ingest/v1/events")
                .header("X-API-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    event(UUID.randomUUID())
                        .replace("PaymentException", "GatewayTimeoutException")))
        .andExpect(status().isAccepted());

    UUID firstIncident =
        jdbc.queryForObject(
            "select id from intelligence.incident_groups where project_id=? and title='com.example.PaymentException'",
            UUID.class,
            UUID.fromString(project));
    UUID secondIncident =
        jdbc.queryForObject(
            "select id from intelligence.incident_groups where project_id=? and title='com.example.GatewayTimeoutException'",
            UUID.class,
            UUID.fromString(project));
    String firstResponse =
        mvc.perform(
                post("/api/v1/incidents/{id}/ai/analyze", firstIncident)
                    .with(csrf())
                    .header("Authorization", "Bearer " + access))
            .andExpect(status().isAccepted())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String firstAnalysis = mapper.readTree(firstResponse).path("analysisId").asText();
    mvc.perform(
            post("/api/v1/incidents/{id}/ai/analyze", firstIncident)
                .with(csrf())
                .header("Authorization", "Bearer " + access))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.analysisId").value(firstAnalysis));
    mvc.perform(
            post("/api/v1/incidents/{id}/ai/analyze", secondIncident)
                .with(csrf())
                .header("Authorization", "Bearer " + access))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("AI_DAILY_LIMIT_REACHED"));
  }

  @Test
  void concurrentEventsGroupOnceAndIncidentWorkflowIsTenantScoped() throws Exception {
    JsonNode owner = mapper.readTree(register("owner@example.test", "Owner"));
    String ownerAccess = owner.path("accessToken").asText();
    UUID ownerId = UUID.fromString(owner.path("account").path("id").asText());
    String projectId = createProject(ownerAccess);
    String key = createKey(ownerAccess, projectId);
    int eventCount = 12;
    var ready = new CountDownLatch(eventCount);
    var start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(eventCount);
    try {
      List<java.util.concurrent.Future<MvcResult>> requests = new ArrayList<>();
      for (int i = 0; i < eventCount; i++) {
        UUID clientId = UUID.randomUUID();
        requests.add(
            executor.submit(
                () -> {
                  ready.countDown();
                  if (!start.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("start timeout");
                  return mvc.perform(
                          post("/ingest/v1/events")
                              .header("X-API-Key", key)
                              .contentType(MediaType.APPLICATION_JSON)
                              .content(event(clientId)))
                      .andExpect(status().isAccepted())
                      .andReturn();
                }));
      }
      assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (var request : requests)
        assertThat(request.get(30, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(202);
    } finally {
      executor.shutdownNow();
    }

    UUID project = UUID.fromString(projectId);
    UUID incidentId =
        jdbc.queryForObject(
            "select id from intelligence.incident_groups where project_id=?", UUID.class, project);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from intelligence.incident_groups where project_id=?",
                Integer.class,
                project))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select occurrence_count from intelligence.incident_groups where id=?",
                Long.class,
                incidentId))
        .isEqualTo((long) eventCount);

    String separateEvent =
        event(UUID.randomUUID()).replace("PaymentException", "GatewayTimeoutException");
    mvc.perform(
            post("/ingest/v1/events")
                .header("X-API-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(separateEvent))
        .andExpect(status().isAccepted());
    UUID similarIncidentId =
        jdbc.queryForObject(
            "select id from intelligence.incident_groups where project_id=? and title='com.example.GatewayTimeoutException'",
            UUID.class,
            project);

    mvc.perform(
            get("/api/v1/projects/{projectId}/incidents", projectId)
                .header("Authorization", "Bearer " + ownerAccess)
                .param("status", "OPEN")
                .param("q", "PaymentException")
                .param("environment", "production")
                .param("release", "release-42")
                .param("size", "1"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.items[0].occurrenceCount").value(eventCount));

    mvc.perform(
            patch("/api/v1/incidents/{id}", incidentId)
                .with(csrf())
                .header("Authorization", "Bearer " + ownerAccess)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"RESOLVED\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RESOLUTION_REQUIRED"));
    mvc.perform(
            patch("/api/v1/incidents/{id}", incidentId)
                .with(csrf())
                .header("Authorization", "Bearer " + ownerAccess)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of("status", "ACKNOWLEDGED", "assigneeId", ownerId))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"));
    mvc.perform(
            post("/api/v1/incidents/{id}/comments", incidentId)
                .with(csrf())
                .header("Authorization", "Bearer " + ownerAccess)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"Investigating payment service\"}"))
        .andExpect(status().isCreated());
    mvc.perform(
            post("/api/v1/incidents/{id}/resolve", incidentId)
                .with(csrf())
                .header("Authorization", "Bearer " + ownerAccess)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"summary\":\"Database pool exhausted\",\"rootCause\":\"Connections were not released\"}"))
        .andExpect(status().isCreated());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from intelligence.embedding_jobs where incident_group_id=? and source_type='RESOLUTION' and source_id in (select id from intelligence.incident_resolutions where incident_group_id=?)",
                Integer.class,
                incidentId,
                incidentId))
        .isEqualTo(1);
    mvc.perform(
            get("/api/v1/incidents/{id}", incidentId)
                .header("Authorization", "Bearer " + ownerAccess))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.incident.status").value("RESOLVED"))
        .andExpect(jsonPath("$.recentEvents.length()").value(eventCount))
        .andExpect(jsonPath("$.comments[0].body").value("Investigating payment service"))
        .andExpect(jsonPath("$.resolutions[0].rootCause").value("Connections were not released"));

    String analysisId =
        mapper
            .readTree(
                mvc.perform(
                        post("/api/v1/incidents/{id}/ai/analyze", incidentId)
                            .with(csrf())
                            .header("Authorization", "Bearer " + ownerAccess))
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.status").value("QUEUED"))
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("analysisId")
            .asText();
    UUID analysisUuid = UUID.fromString(analysisId);
    String rawSecret = "sk-proj-abcdefghijklmnopqrstuvwxyz0123456789";
    UUID eventId =
        jdbc.queryForObject(
            "select event_id from intelligence.incident_occurrences where incident_group_id=? order by occurred_at desc,event_id desc limit 1",
            UUID.class,
            incidentId);
    jdbc.update(
        "update intelligence.error_events set message=? where id=?", "token=" + rawSecret, eventId);
    String duplicateAnalysisId =
        mapper
            .readTree(
                mvc.perform(
                        post("/api/v1/incidents/{id}/ai/analyze", incidentId)
                            .with(csrf())
                            .header("Authorization", "Bearer " + ownerAccess))
                    .andExpect(status().isAccepted())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("analysisId")
            .asText();
    assertThat(duplicateAnalysisId).isEqualTo(analysisId);
    Result fallback =
        new Result(
            Category.UNKNOWN,
            0,
            "Manual investigation required.",
            List.of(new Evidence("message", "payment rejected")),
            List.of(),
            List.of(
                new DiagnosticStep(
                    1,
                    "Review the sanitized stack trace.",
                    "Find the first application-owned frame.",
                    RiskLevel.LOW)),
            List.of(),
            List.of("Provider unavailable."));
    var firstClaim = aiQueue.claimNext();
    assertThat(firstClaim.id()).isEqualTo(analysisUuid);
    assertThat(aiQueue.input(analysisUuid).events().stream().map(e -> e.message()).toList())
        .anySatisfy(
            message -> assertThat(message).contains("[REDACTED]").doesNotContain(rawSecret));
    aiQueue.failed(firstClaim, "PROVIDER_UNAVAILABLE", fallback);
    assertThat(
            jdbc.queryForObject(
                "select status from intelligence.incident_ai_analyses where id=?",
                String.class,
                analysisUuid))
        .isEqualTo("RETRYING");
    jdbc.update(
        "update intelligence.incident_ai_analyses set next_attempt_at=now() - interval '1 second' where id=?",
        analysisUuid);
    var secondClaim = aiQueue.claimNext();
    assertThat(secondClaim.id()).isEqualTo(analysisUuid);
    aiQueue.failed(secondClaim, "PROVIDER_UNAVAILABLE", fallback);
    mvc.perform(
            get("/api/v1/incidents/{incidentId}/ai/analyses/{analysisId}", incidentId, analysisId)
                .header("Authorization", "Bearer " + ownerAccess))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FALLBACK"))
        .andExpect(jsonPath("$.result.category").value("UNKNOWN"))
        .andExpect(jsonPath("$.promptVersion").value("diagnostic-v1"));

    JsonNode outsider = mapper.readTree(register("outsider@example.test", "Outsider"));
    String outsiderProjectId = createProject(outsider.path("accessToken").asText());
    String outsiderKey = createKey(outsider.path("accessToken").asText(), outsiderProjectId);
    mvc.perform(
            post("/ingest/v1/events")
                .header("X-API-Key", outsiderKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    event(UUID.randomUUID()).replace("PaymentException", "IllegalStateException")))
        .andExpect(status().isAccepted());
    UUID outsiderIncidentId =
        jdbc.queryForObject(
            "select id from intelligence.incident_groups where project_id=?",
            UUID.class,
            UUID.fromString(outsiderProjectId));
    float[] fixtureVector = new float[1536];
    fixtureVector[0] = 1f;
    String vectorLiteral = vectorLiteral(fixtureVector);
    UUID relatedResolutionId = UUID.randomUUID();
    jdbc.update(
        "insert into intelligence.incident_resolutions(id,incident_group_id,summary,root_cause,verified_by) values(?,?,?,?,?)",
        relatedResolutionId,
        similarIncidentId,
        "Increase the gateway timeout.",
        "The upstream timeout was too low.",
        ownerId);
    insertIncidentEmbedding(
        project,
        similarIncidentId,
        similarIncidentId,
        owner.path("account").path("activeOrganizationId").asText(),
        vectorLiteral);
    jdbc.update(
        "insert into intelligence.incident_embeddings(id,organization_id,project_id,incident_group_id,source_type,source_id,embedding_model,source_text,embedding) values(?,?,?,?,? ,?,?,?,?::vector)",
        UUID.randomUUID(),
        UUID.fromString(owner.path("account").path("activeOrganizationId").asText()),
        project,
        similarIncidentId,
        "RESOLUTION",
        relatedResolutionId,
        "test-embedding-v1",
        "Increase the gateway timeout. The upstream timeout was too low.",
        vectorLiteral);
    insertIncidentEmbedding(
        UUID.fromString(outsiderProjectId),
        outsiderIncidentId,
        outsiderIncidentId,
        outsider.path("account").path("activeOrganizationId").asText(),
        vectorLiteral);
    org.mockito.Mockito.when(embeddingProvider.embed(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(fixtureVector);
    org.mockito.Mockito.when(embeddingProvider.model()).thenReturn("test-embedding-v1");
    assertThat(
            similarityService
                .enrichForAnalysis(analysisUuid, aiQueue.input(analysisUuid))
                .resolutions()
                .stream()
                .map(resolution -> resolution.id())
                .toList())
        .contains(relatedResolutionId);
    mvc.perform(
            post("/api/v1/incidents/{id}/similar", incidentId)
                .with(csrf())
                .header("Authorization", "Bearer " + ownerAccess))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.embeddingModel").value("test-embedding-v1"))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].incidentId").value(similarIncidentId.toString()));
    mvc.perform(
            get("/api/v1/projects/{projectId}/analytics/overview", projectId)
                .header("Authorization", "Bearer " + ownerAccess)
                .param("days", "30"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.eventCount").value(eventCount + 1))
        .andExpect(jsonPath("$.incidentCount").value(2))
        .andExpect(jsonPath("$.openCount").value(1))
        .andExpect(jsonPath("$.resolvedCount").value(1))
        .andExpect(jsonPath("$.resolutionRate").value(0.5))
        .andExpect(jsonPath("$.dailyEvents.length()").value(30))
        .andExpect(jsonPath("$.topIncidents[0].occurrenceCount").value(eventCount));
    mvc.perform(
            get("/api/v1/projects/{projectId}/analytics/overview", outsiderProjectId)
                .header("Authorization", "Bearer " + ownerAccess))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/audit").header("Authorization", "Bearer " + ownerAccess))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items").isArray())
        .andExpect(jsonPath("$.items[0].action").exists());
    mvc.perform(
            get("/api/v1/incidents/{id}", incidentId)
                .header("Authorization", "Bearer " + outsider.path("accessToken").asText()))
        .andExpect(status().isNotFound());
    mvc.perform(
            get("/api/v1/incidents/{incidentId}/ai/analyses/{analysisId}", incidentId, analysisId)
                .header("Authorization", "Bearer " + outsider.path("accessToken").asText()))
        .andExpect(status().isNotFound());
    register("viewer@example.test", "Viewer");
    mvc.perform(
            post(
                    "/api/v1/organizations/{organizationId}/members",
                    owner.path("account").path("activeOrganizationId").asText())
                .with(csrf())
                .header("Authorization", "Bearer " + ownerAccess)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"viewer@example.test\",\"role\":\"VIEWER\"}"))
        .andExpect(status().isCreated());
    JsonNode viewerSession = mapper.readTree(login("viewer@example.test"));
    String viewerAccess = viewerSession.path("accessToken").asText();
    viewerAccess =
        mapper
            .readTree(
                mvc.perform(
                        post("/api/v1/auth/organization")
                            .with(csrf())
                            .header("Authorization", "Bearer " + viewerAccess)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(
                                mapper.writeValueAsString(
                                    Map.of(
                                        "organizationId",
                                        owner
                                            .path("account")
                                            .path("activeOrganizationId")
                                            .asText()))))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString())
            .path("accessToken")
            .asText();
    mvc.perform(
            post("/api/v1/incidents/{id}/comments", incidentId)
                .with(csrf())
                .header("Authorization", "Bearer " + viewerAccess)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"body\":\"viewer cannot write\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/incidents/{id}/ai/analyze", incidentId)
                .with(csrf())
                .header("Authorization", "Bearer " + viewerAccess))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/v1/incidents/{id}/similar", incidentId)
                .with(csrf())
                .header("Authorization", "Bearer " + viewerAccess))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/audit").header("Authorization", "Bearer " + viewerAccess))
        .andExpect(status().isForbidden());
  }

  private String register(String email, String name) throws Exception {
    mvc.perform(
            post("/api/v1/auth/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of(
                            "email",
                            email,
                            "password",
                            "a-strong-password-123",
                            "displayName",
                            name,
                            "organizationName",
                            name + " organization"))))
        .andExpect(status().isAccepted());
    TestAuthMail.verify(mvc, jdbc, mail, email);
    return login(email);
  }

  private String login(String email) throws Exception {
    return mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        Map.of("email", email, "password", "a-strong-password-123"))))
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

  private String createKey(String access, String projectId) throws Exception {
    String response =
        mvc.perform(
                post("/api/v1/projects/{id}/keys", projectId)
                    .with(csrf())
                    .header("Authorization", "Bearer " + access)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"label\":\"integration\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return mapper.readTree(response).path("secret").asText();
  }

  private String event(UUID clientId) throws Exception {
    return mapper.writeValueAsString(
        Map.of(
            "eventId",
            clientId,
            "occurredAt",
            Instant.now().toString(),
            "environment",
            "production",
            "release",
            "release-42",
            "exceptionType",
            "com.example.PaymentException",
            "message",
            "payment rejected",
            "stackTrace",
            "com.example.PaymentException: payment rejected\n at com.example.Payments.charge(Payments.java:42)\n at com.example.Gateway.post(Gateway.java:18)",
            "request",
            Map.of("method", "POST", "path", "/api/payments/123?request_id=" + clientId)));
  }

  private void insertIncidentEmbedding(
      UUID projectId, UUID incidentId, UUID sourceId, String organizationId, String vector)
      throws Exception {
    jdbc.update(
        "insert into intelligence.incident_embeddings(id,organization_id,project_id,incident_group_id,source_type,source_id,embedding_model,source_text,embedding) values(?,?,?,?,? ,?,?,?,?::vector)",
        UUID.randomUUID(),
        UUID.fromString(organizationId),
        projectId,
        incidentId,
        "INCIDENT",
        sourceId,
        "test-embedding-v1",
        "fixture evidence",
        vector);
  }

  private String vectorLiteral(float[] values) {
    StringBuilder result = new StringBuilder("[");
    for (int index = 0; index < values.length; index++) {
      if (index > 0) result.append(',');
      result.append(values[index]);
    }
    return result.append(']').toString();
  }
}

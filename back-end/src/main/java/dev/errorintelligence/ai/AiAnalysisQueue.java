package dev.errorintelligence.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.errorintelligence.ai.DiagnosticDtos.Result;
import dev.errorintelligence.ingestion.SensitiveDataRedactor;
import dev.errorintelligence.similarity.AnalysisEvidence.AnalysisInput;
import dev.errorintelligence.similarity.AnalysisEvidence.EventEvidence;
import dev.errorintelligence.similarity.AnalysisEvidence.ResolutionEvidence;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiAnalysisQueue {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final SensitiveDataRedactor redactor;
  private final int maxAttempts;
  private final Duration retryDelay;

  public AiAnalysisQueue(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      SensitiveDataRedactor redactor,
      @Value("${app.ai.max-attempts:2}") int maxAttempts,
      @Value("${app.ai.retry-delay:PT15S}") Duration retryDelay) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.redactor = redactor;
    this.maxAttempts = Math.max(1, maxAttempts);
    this.retryDelay = retryDelay;
  }

  @Transactional
  public Claim claimNext() {
    jdbc.update(
        "update intelligence.incident_ai_analyses set status='FALLBACK',category='UNKNOWN',failure_code='CLAIM_EXPIRED',claim_token=null,updated_at=now(),finished_at=now() where status='RUNNING' and updated_at<now()-interval '2 minutes' and attempt_count>=?",
        maxAttempts);
    List<UUID> ids =
        jdbc.query(
            "select id from intelligence.incident_ai_analyses where attempt_count<? and ((status in ('QUEUED','RETRYING') and next_attempt_at<=now()) or (status='RUNNING' and updated_at<now() - interval '2 minutes')) order by created_at,id limit 1 for update skip locked",
            (rs, row) -> rs.getObject(1, UUID.class),
            maxAttempts);
    if (ids.isEmpty()) return null;
    UUID id = ids.getFirst();
    UUID token = UUID.randomUUID();
    jdbc.update(
        "update intelligence.incident_ai_analyses set status='RUNNING',claim_token=?,attempt_count=attempt_count+1,updated_at=now() where id=?",
        token,
        id);
    return new Claim(id, token);
  }

  @Transactional(readOnly = true)
  public AnalysisInput input(UUID analysisId) {
    List<String> title =
        jdbc.query(
            "select i.title from intelligence.incident_ai_analyses a join intelligence.incident_groups i on i.id=a.incident_group_id and i.organization_id=a.organization_id where a.id=?",
            (rs, row) -> rs.getString(1),
            analysisId);
    if (title.isEmpty()) throw new IllegalStateException("Analysis job is missing.");
    List<EventEvidence> events =
        jdbc.query(
            "select e.id,e.exception_type,e.message,e.stack_trace,coalesce(e.request_metadata->>'path','') from intelligence.incident_ai_analyses a join intelligence.incident_occurrences o on o.incident_group_id=a.incident_group_id join intelligence.error_events e on e.id=o.event_id where a.id=? order by o.occurred_at desc,o.event_id desc limit 5",
            (rs, row) -> event(rs),
            analysisId);
    List<ResolutionEvidence> resolutions =
        jdbc.query(
            "select r.id,r.summary,r.root_cause from intelligence.incident_ai_analyses a join intelligence.incident_resolutions r on r.incident_group_id=a.incident_group_id where a.id=? order by r.verified_at desc,r.id desc limit 3",
            (rs, row) ->
                new ResolutionEvidence(
                    rs.getObject(1, UUID.class),
                    clean(rs.getString(2), 500),
                    clean(rs.getString(3), 1000)),
            analysisId);
    return new AnalysisInput(clean(title.getFirst(), 500), events, resolutions);
  }

  @Transactional
  public void complete(Claim claim, DiagnosticProvider.ProviderResult providerResult) {
    Result result = providerResult.result();
    jdbc.update(
        "update intelligence.incident_ai_analyses set status='SUCCEEDED',claim_token=null,category=?,model=?,result_json=?::jsonb,failure_code=null,input_tokens=?,output_tokens=?,updated_at=now(),finished_at=now() where id=? and claim_token=? and status='RUNNING' and updated_at>=now()-interval '2 minutes'",
        result.category().name(),
        providerResult.model(),
        json(result),
        providerResult.inputTokens(),
        providerResult.outputTokens(),
        claim.id(),
        claim.token());
  }

  @Transactional
  public void failed(Claim claim, String code, Result fallback) {
    var owned =
        jdbc.query(
            "select attempt_count from intelligence.incident_ai_analyses where id=? and claim_token=? and status='RUNNING' and updated_at>=now()-interval '2 minutes' for update",
            (rs, row) -> rs.getInt(1),
            claim.id(),
            claim.token());
    if (owned.isEmpty()) return;
    int attempts = owned.getFirst();
    if (attempts < maxAttempts) {
      jdbc.update(
          "update intelligence.incident_ai_analyses set status='RETRYING',claim_token=null,failure_code=?,next_attempt_at=now() + (? * interval '1 millisecond'),updated_at=now() where id=?",
          safeCode(code),
          Math.max(0, retryDelay.toMillis()),
          claim.id());
      return;
    }
    jdbc.update(
        "update intelligence.incident_ai_analyses set status='FALLBACK',claim_token=null,category='UNKNOWN',failure_code=?,result_json=?::jsonb,updated_at=now(),finished_at=now() where id=?",
        safeCode(code),
        json(fallback),
        claim.id());
  }

  public record Claim(UUID id, UUID token) {}

  private EventEvidence event(ResultSet rs) throws SQLException {
    return new EventEvidence(
        rs.getObject(1, UUID.class),
        clean(rs.getString(2), 300),
        clean(rs.getString(3), 2000),
        clean(rs.getString(4), 8000),
        clean(rs.getString(5), 300));
  }

  private String clean(String value, int max) {
    String safe = redactor.text(value == null ? "" : value);
    return safe.length() <= max ? safe : safe.substring(0, max);
  }

  private String safeCode(String value) {
    return value == null || !value.matches("[A-Z0-9_]{1,40}") ? "PROVIDER_FAILURE" : value;
  }

  private String json(Result value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not serialize a validated diagnostic.", exception);
    }
  }
}

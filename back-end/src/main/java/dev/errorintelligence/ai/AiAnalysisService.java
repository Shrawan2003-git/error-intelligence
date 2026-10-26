package dev.errorintelligence.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.errorintelligence.ai.DiagnosticDtos.Accepted;
import dev.errorintelligence.ai.DiagnosticDtos.Analysis;
import dev.errorintelligence.ai.DiagnosticDtos.Result;
import dev.errorintelligence.ai.DiagnosticDtos.Status;
import dev.errorintelligence.common.api.DomainException;
import dev.errorintelligence.identity.IdentityDtos.Tenant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiAnalysisService {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final String model;
  private final int dailyLimit;

  public AiAnalysisService(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      @Value("${app.ai.openai.model:gpt-4.1-mini}") String model,
      @Value("${app.ai.daily-limit:100}") int dailyLimit) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.model = model;
    if (dailyLimit < 1) throw new IllegalArgumentException("AI daily limit must be positive.");
    this.dailyLimit = dailyLimit;
  }

  @Transactional
  public Accepted enqueue(Tenant tenant, UUID incidentId) {
    tenant.requireContributor();
    Integer incidentExists =
        jdbc.queryForObject(
            "select count(*) from intelligence.incident_groups where id=? and organization_id=?",
            Integer.class,
            incidentId,
            tenant.organizationId());
    if (incidentExists == null || incidentExists == 0) throw DomainException.missing();

    // Serialize quota decisions across app instances without adding a mutable counter table.
    jdbc.queryForObject(
        "select id from intelligence.organizations where id=? for update",
        UUID.class,
        tenant.organizationId());
    var active =
        jdbc.query(
            "select id,status,created_at from intelligence.incident_ai_analyses where incident_group_id=? and organization_id=? and status in ('QUEUED','RUNNING','RETRYING') order by created_at desc,id desc limit 1",
            (rs, row) ->
                new Accepted(
                    rs.getObject(1, UUID.class), Status.valueOf(rs.getString(2)), instant(rs, 3)),
            incidentId,
            tenant.organizationId());
    if (!active.isEmpty()) return active.getFirst();
    Long usedToday =
        jdbc.queryForObject(
            "select count(*) from intelligence.incident_ai_analyses where organization_id=? and created_at>=?",
            Long.class,
            tenant.organizationId(),
            Timestamp.from(Instant.now().truncatedTo(ChronoUnit.DAYS)));
    if (usedToday != null && usedToday >= dailyLimit)
      throw new DomainException(
          HttpStatus.TOO_MANY_REQUESTS,
          "AI_DAILY_LIMIT_REACHED",
          "The organization's daily AI analysis limit has been reached.");

    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    int inserted =
        jdbc.update(
            "insert into intelligence.incident_ai_analyses(id,organization_id,project_id,incident_group_id,status,model,prompt_version) select ?,organization_id,project_id,id,'QUEUED',?,'diagnostic-v1' from intelligence.incident_groups where id=? and organization_id=? on conflict (incident_group_id) where status in ('QUEUED','RUNNING','RETRYING') do nothing",
            id,
            model,
            incidentId,
            tenant.organizationId());
    if (inserted == 0) {
      return jdbc.queryForObject(
          "select id,status,created_at from intelligence.incident_ai_analyses where incident_group_id=? and organization_id=? and status in ('QUEUED','RUNNING','RETRYING') order by created_at desc,id desc limit 1",
          (rs, row) ->
              new Accepted(
                  rs.getObject(1, UUID.class), Status.valueOf(rs.getString(2)), instant(rs, 3)),
          incidentId,
          tenant.organizationId());
    }
    jdbc.update(
        "insert into intelligence.audit_events(id,organization_id,actor_id,action,target_id) values(?,?,?,?,?)",
        UUID.randomUUID(),
        tenant.organizationId(),
        tenant.userId(),
        "INCIDENT_AI_ANALYSIS_REQUESTED",
        incidentId);
    return new Accepted(id, Status.QUEUED, now);
  }

  @Transactional(readOnly = true)
  public Analysis get(Tenant tenant, UUID incidentId, UUID analysisId) {
    return jdbc
        .query(
            "select a.id,a.incident_group_id,a.status,a.model,a.prompt_version,a.result_json::text,a.failure_code,a.attempt_count,a.input_tokens,a.output_tokens,a.created_at,a.updated_at,a.finished_at from intelligence.incident_ai_analyses a join intelligence.incident_groups i on i.id=a.incident_group_id and i.organization_id=a.organization_id where a.id=? and a.incident_group_id=? and a.organization_id=?",
            (rs, row) -> analysis(rs),
            analysisId,
            incidentId,
            tenant.organizationId())
        .stream()
        .findFirst()
        .orElseThrow(DomainException::missing);
  }

  private Analysis analysis(ResultSet rs) throws SQLException {
    Result result = null;
    String json = rs.getString(6);
    if (json != null) {
      try {
        result = mapper.readValue(json, Result.class);
      } catch (JsonProcessingException exception) {
        throw new IllegalStateException("Stored diagnostic result is invalid.", exception);
      }
    }
    return new Analysis(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        Status.valueOf(rs.getString(3)),
        rs.getString(4),
        rs.getString(5),
        result,
        rs.getString(7),
        rs.getInt(8),
        (Integer) rs.getObject(9),
        (Integer) rs.getObject(10),
        instant(rs, 11),
        instant(rs, 12),
        nullableInstant(rs, 13));
  }

  private Instant instant(ResultSet rs, int index) throws SQLException {
    return rs.getTimestamp(index).toInstant();
  }

  private Instant nullableInstant(ResultSet rs, int index) throws SQLException {
    Timestamp value = rs.getTimestamp(index);
    return value == null ? null : value.toInstant();
  }
}

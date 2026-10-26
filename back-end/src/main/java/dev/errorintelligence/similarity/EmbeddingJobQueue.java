package dev.errorintelligence.similarity;

import dev.errorintelligence.common.security.Credentials;
import dev.errorintelligence.ingestion.SensitiveDataRedactor;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmbeddingJobQueue {
  private final JdbcTemplate jdbc;
  private final Duration retryDelay;
  private final int maxAttempts;
  private final SensitiveDataRedactor redactor;
  private final String model;

  public EmbeddingJobQueue(
      JdbcTemplate jdbc,
      @Value("${app.ai.embedding-retry-delay:PT30S}") Duration retryDelay,
      @Value("${app.ai.embedding-max-attempts:3}") int maxAttempts,
      SensitiveDataRedactor redactor,
      @Value("${app.ai.embedding-model:text-embedding-3-small}") String model) {
    this.jdbc = jdbc;
    this.retryDelay = retryDelay;
    this.maxAttempts = Math.max(1, maxAttempts);
    this.redactor = redactor;
    this.model = model;
  }

  @Transactional
  public void enqueueIncident(UUID organizationId, UUID projectId, UUID incidentId, String text) {
    enqueue(organizationId, projectId, incidentId, "INCIDENT", incidentId, text);
  }

  @Transactional
  public void enqueueResolution(
      UUID organizationId, UUID projectId, UUID incidentId, UUID resolutionId, String text) {
    enqueue(organizationId, projectId, incidentId, "RESOLUTION", resolutionId, text);
  }

  @Transactional
  public Job claimNext() {
    jdbc.update(
        "update intelligence.embedding_jobs set status='QUEUED',attempt_count=0,claim_token=null,next_attempt_at=now() where status='RUNNING' and updated_at<now()-interval '2 minutes' and source_version<>claimed_version");
    jdbc.update(
        "update intelligence.embedding_jobs set status='FAILED',failure_code='CLAIM_EXPIRED',claim_token=null,updated_at=now() where status='RUNNING' and updated_at<now()-interval '2 minutes' and attempt_count>=?",
        maxAttempts);
    List<UUID> ids =
        jdbc.query(
            "select id from intelligence.embedding_jobs where attempt_count<? and ((status in ('QUEUED','RETRYING') and next_attempt_at<=now()) or (status='RUNNING' and updated_at<now()-interval '2 minutes')) order by created_at,id limit 1 for update skip locked",
            (rs, row) -> rs.getObject(1, UUID.class),
            maxAttempts);
    if (ids.isEmpty()) return null;
    UUID id = ids.getFirst(), token = UUID.randomUUID();
    jdbc.update(
        "update intelligence.embedding_jobs set status='RUNNING',claim_token=?,claimed_version=source_version,attempt_count=attempt_count+1,updated_at=now() where id=?",
        token,
        id);
    return jdbc.queryForObject(
        "select source_version,source_text,organization_id from intelligence.embedding_jobs where id=?",
        (rs, row) ->
            new Job(id, token, rs.getLong(1), rs.getString(2), rs.getObject(3, UUID.class)),
        id);
  }

  @Transactional
  public void complete(Job job, float[] vector, String embeddingModel) {
    String literal = vectorLiteral(vector);
    if (!owns(job)) return;
    if (sourceChanged(job)) {
      requeue(job);
      return;
    }
    jdbc.update(
        "insert into intelligence.incident_embeddings(id,organization_id,project_id,incident_group_id,source_type,source_id,embedding_model,source_text,embedding) select gen_random_uuid(),organization_id,project_id,incident_group_id,source_type,source_id,?,?,?::vector from intelligence.embedding_jobs where id=? on conflict(organization_id,project_id,source_type,source_id) do update set embedding_model=excluded.embedding_model,source_text=excluded.source_text,embedding=excluded.embedding,updated_at=now()",
        embeddingModel,
        job.sourceText(),
        literal,
        job.id());
    jdbc.update(
        "update intelligence.embedding_jobs set status='SUCCEEDED',claim_token=null,failure_code=null,updated_at=now() where id=?",
        job.id());
  }

  @Transactional
  public void failed(Job job, String code) {
    if (!owns(job)) return;
    if (sourceChanged(job)) {
      requeue(job);
      return;
    }
    int attempts =
        jdbc.queryForObject(
            "select attempt_count from intelligence.embedding_jobs where id=?",
            Integer.class,
            job.id());
    String safe = code != null && code.matches("[A-Z0-9_]{1,40}") ? code : "EMBEDDING_FAILED";
    jdbc.update(
        "update intelligence.embedding_jobs set status=?,claim_token=null,failure_code=?,next_attempt_at=now()+(?*interval '1 millisecond'),updated_at=now() where id=?",
        attempts < maxAttempts ? "RETRYING" : "FAILED",
        safe,
        Math.max(0, retryDelay.toMillis()),
        job.id());
  }

  private boolean owns(Job job) {
    return !jdbc.query(
            "select id from intelligence.embedding_jobs where id=? and claim_token=? and status='RUNNING' and updated_at>=now()-interval '2 minutes' for update",
            (rs, row) -> rs.getObject(1, UUID.class),
            job.id(),
            job.claimToken())
        .isEmpty();
  }

  private boolean sourceChanged(Job job) {
    return jdbc.queryForObject(
            "select source_version from intelligence.embedding_jobs where id=?",
            Long.class,
            job.id())
        != job.sourceVersion();
  }

  private void requeue(Job job) {
    jdbc.update(
        "update intelligence.embedding_jobs set status='QUEUED',attempt_count=0,claim_token=null,failure_code=null,next_attempt_at=now(),updated_at=now() where id=?",
        job.id());
  }

  private void enqueue(
      UUID organizationId,
      UUID projectId,
      UUID incidentId,
      String sourceType,
      UUID sourceId,
      String text) {
    String bounded = redactor.text(text == null ? "" : text).strip();
    if (bounded.isBlank()) return;
    if (bounded.length() > 12000) bounded = bounded.substring(0, 12000);
    jdbc.update(
        "insert into intelligence.embedding_jobs(id,organization_id,project_id,incident_group_id,source_type,source_id,source_text,source_hash,status) values(?,?,?,?,?,?,?,?,'QUEUED') on conflict(organization_id,project_id,source_type,source_id) do update set source_text=excluded.source_text,source_hash=excluded.source_hash,source_version=intelligence.embedding_jobs.source_version+1,status=case when intelligence.embedding_jobs.status='RUNNING' then 'RUNNING' else 'QUEUED' end,attempt_count=case when intelligence.embedding_jobs.status='RUNNING' then intelligence.embedding_jobs.attempt_count else 0 end,failure_code=null,next_attempt_at=now() where intelligence.embedding_jobs.source_hash<>excluded.source_hash",
        UUID.randomUUID(),
        organizationId,
        projectId,
        incidentId,
        sourceType,
        sourceId,
        bounded,
        Credentials.digest(model + "\n" + bounded));
  }

  private String vectorLiteral(float[] vector) {
    if (vector.length != 1536)
      throw new IllegalArgumentException("Expected a 1536-value embedding.");
    StringBuilder value = new StringBuilder("[");
    for (int i = 0; i < vector.length; i++) {
      if (!Float.isFinite(vector[i]))
        throw new IllegalArgumentException("Embedding vector is invalid.");
      if (i > 0) value.append(',');
      value.append(Float.toString(vector[i]));
    }
    return value.append(']').toString();
  }

  public record Job(
      UUID id, UUID claimToken, long sourceVersion, String sourceText, UUID organizationId) {}
}

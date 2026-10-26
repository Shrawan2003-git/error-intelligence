package dev.errorintelligence.ingestion;

import static dev.errorintelligence.ingestion.IngestionDtos.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.errorintelligence.common.api.DomainException;
import dev.errorintelligence.common.security.Credentials;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IngestionService {
  private final JdbcTemplate jdbc;
  private final SensitiveDataRedactor redactor;
  private final ObjectMapper mapper;
  private final ApplicationEventPublisher events;

  public IngestionService(
      JdbcTemplate jdbc,
      SensitiveDataRedactor redactor,
      ObjectMapper mapper,
      ApplicationEventPublisher events) {
    this.jdbc = jdbc;
    this.redactor = redactor;
    this.mapper = mapper;
    this.events = events;
  }

  @Transactional
  public Receipt accept(String apiKey, EventInput input) {
    if (apiKey == null || apiKey.length() != 46 || !apiKey.startsWith("ei_")) throw invalidKey();
    KeyOwner owner =
        jdbc
            .query(
                "select k.id,k.project_id,p.organization_id from intelligence.ingestion_keys k join intelligence.projects p on p.id=k.project_id where k.digest=? and k.scope='INGEST' and k.revoked_at is null and p.archived_at is null",
                (rs, row) ->
                    new KeyOwner(
                        rs.getObject(1, UUID.class),
                        rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class)),
                Credentials.digest(apiKey))
            .stream()
            .findFirst()
            .orElseThrow(this::invalidKey);

    Instant now = Instant.now();
    if (input.occurredAt().isAfter(now.plusSeconds(300)))
      throw new DomainException(
          HttpStatus.BAD_REQUEST,
          "INVALID_EVENT_TIME",
          "The event time cannot be more than five minutes in the future.");

    var existing =
        jdbc.query(
            "select event_id,accepted_at from intelligence.ingestion_receipts where project_id=? and client_event_id=?",
            (rs, row) ->
                new Receipt(
                    rs.getObject(1, UUID.class),
                    owner.project(),
                    true,
                    rs.getTimestamp(2).toInstant()),
            owner.project(),
            input.eventId());
    if (!existing.isEmpty()) return existing.getFirst();

    UUID environment = environment(owner.project(), clean(input.environment(), 80));
    UUID release =
        input.release() == null || input.release().isBlank()
            ? null
            : release(owner.project(), clean(input.release(), 120));
    UUID id = UUID.randomUUID();
    Map<String, Object> request = redactor.request(input.request());
    Map<String, String> environmentValues = redactor.environment(input.environmentValues());
    jdbc.update(
        "insert into intelligence.error_events(id,organization_id,project_id,environment_id,release_id,client_event_id,occurred_at,exception_type,message,stack_trace,request_metadata,environment_metadata) values(?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb) on conflict(project_id,client_event_id) do nothing",
        id,
        owner.organization(),
        owner.project(),
        environment,
        release,
        input.eventId(),
        Timestamp.from(input.occurredAt()),
        clean(input.exceptionType(), 500),
        clean(input.message(), 4000),
        clean(input.stackTrace(), 12000),
        json(request),
        json(environmentValues));
    var saved =
        jdbc.query(
            "select id,received_at from intelligence.error_events where project_id=? and client_event_id=?",
            (rs, row) ->
                new Receipt(
                    rs.getObject(1, UUID.class),
                    owner.project(),
                    !rs.getObject(1, UUID.class).equals(id),
                    rs.getTimestamp(2).toInstant()),
            owner.project(),
            input.eventId());
    Receipt receipt = saved.getFirst();
    jdbc.update(
        "insert into intelligence.ingestion_receipts(project_id,client_event_id,event_id,accepted_at) values(?,?,?,?) on conflict(project_id,client_event_id) do nothing",
        owner.project(),
        input.eventId(),
        receipt.eventId(),
        Timestamp.from(receipt.acceptedAt()));
    if (!receipt.duplicate()) events.publishEvent(new ErrorEventStored(receipt.eventId()));
    return receipt;
  }

  private UUID environment(UUID project, String name) {
    jdbc.update(
        "insert into intelligence.environments(id,project_id,name) values(?,?,?) on conflict(project_id,name) do nothing",
        UUID.randomUUID(),
        project,
        name);
    return jdbc.queryForObject(
        "select id from intelligence.environments where project_id=? and name=?",
        UUID.class,
        project,
        name);
  }

  private UUID release(UUID project, String version) {
    jdbc.update(
        "insert into intelligence.releases(id,project_id,version) values(?,?,?) on conflict(project_id,version) do nothing",
        UUID.randomUUID(),
        project,
        version);
    return jdbc.queryForObject(
        "select id from intelligence.releases where project_id=? and version=?",
        UUID.class,
        project,
        version);
  }

  private String json(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Sanitized event metadata could not be encoded.");
    }
  }

  private String clean(String value, int maxLength) {
    if (value == null) return "";
    String safe = redactor.text(value.strip());
    if (safe.length() <= maxLength) return safe;
    int end = maxLength;
    if (Character.isHighSurrogate(safe.charAt(end - 1))) end--;
    return safe.substring(0, end);
  }

  private DomainException invalidKey() {
    return new DomainException(
        HttpStatus.UNAUTHORIZED,
        "INVALID_INGESTION_KEY",
        "The ingestion key is invalid or has been revoked.");
  }

  private record KeyOwner(UUID key, UUID project, UUID organization) {}
}

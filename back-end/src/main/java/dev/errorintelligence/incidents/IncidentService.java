package dev.errorintelligence.incidents;

import static dev.errorintelligence.incidents.IncidentDtos.*;

import dev.errorintelligence.common.api.DomainException;
import dev.errorintelligence.identity.IdentityDtos.Tenant;
import dev.errorintelligence.similarity.EmbeddingJobQueue;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IncidentService {
  private final JdbcTemplate jdbc;
  private final FingerprintV1 fingerprints;
  private final EmbeddingJobQueue embeddingJobs;

  public IncidentService(
      JdbcTemplate jdbc, FingerprintV1 fingerprints, EmbeddingJobQueue embeddingJobs) {
    this.jdbc = jdbc;
    this.fingerprints = fingerprints;
    this.embeddingJobs = embeddingJobs;
  }

  @Transactional
  public void group(UUID eventId) {
    EventData event =
        jdbc.queryForObject(
            "select e.id,e.organization_id,e.project_id,e.occurred_at,e.exception_type,e.message,e.stack_trace,coalesce(e.request_metadata->>'path','') from intelligence.error_events e where e.id=?",
            (rs, row) ->
                new EventData(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getObject(3, UUID.class),
                    rs.getTimestamp(4).toInstant(),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getString(7),
                    rs.getString(8)),
            eventId);
    String fingerprint =
        fingerprints.create(event.exceptionType(), event.stackTrace(), event.route());
    int recorded =
        jdbc.update(
            "insert into intelligence.error_fingerprints(event_id,fingerprint_version,fingerprint) values(?,?,?) on conflict(event_id,fingerprint_version) do nothing",
            event.id(),
            FingerprintV1.VERSION,
            fingerprint);
    if (recorded == 0) return;

    UUID groupId =
        jdbc.queryForObject(
            "insert into intelligence.incident_groups(id,organization_id,project_id,fingerprint_version,fingerprint,title,first_seen,last_seen,occurrence_count) values(?,?,?,?,?,?,?,?,1) on conflict(project_id,fingerprint_version,fingerprint) do update set first_seen=least(intelligence.incident_groups.first_seen,excluded.first_seen),last_seen=greatest(intelligence.incident_groups.last_seen,excluded.last_seen),occurrence_count=intelligence.incident_groups.occurrence_count+1,updated_at=now() returning id",
            UUID.class,
            UUID.randomUUID(),
            event.organizationId(),
            event.projectId(),
            FingerprintV1.VERSION,
            fingerprint,
            truncate(event.exceptionType(), 500),
            Timestamp.from(event.occurredAt()),
            Timestamp.from(event.occurredAt()));
    jdbc.update(
        "insert into intelligence.incident_occurrences(incident_group_id,event_id,occurred_at) values(?,?,?)",
        groupId,
        event.id(),
        Timestamp.from(event.occurredAt()));
    embeddingJobs.enqueueIncident(
        event.organizationId(),
        event.projectId(),
        groupId,
        event.exceptionType()
            + "\n"
            + event.message()
            + "\n"
            + event.stackTrace()
            + "\n"
            + event.route());
  }

  public Page<Summary> list(
      Tenant tenant,
      UUID projectId,
      Status status,
      String environment,
      String release,
      String query,
      int page,
      int size) {
    requireProject(tenant, projectId);
    StringBuilder filter = new StringBuilder(" where i.organization_id=? and i.project_id=?");
    var args = new java.util.ArrayList<Object>();
    args.add(tenant.organizationId());
    args.add(projectId);
    if (status != null) {
      filter.append(" and i.status=?");
      args.add(status.name());
    }
    if (environment != null && !environment.isBlank()) {
      filter.append(
          " and exists(select 1 from intelligence.incident_occurrences io join intelligence.error_events e on e.id=io.event_id join intelligence.environments env on env.id=e.environment_id where io.incident_group_id=i.id and env.name=?)");
      args.add(environment.strip());
    }
    if (release != null && !release.isBlank()) {
      filter.append(
          " and exists(select 1 from intelligence.incident_occurrences io join intelligence.error_events e on e.id=io.event_id join intelligence.releases r on r.id=e.release_id where io.incident_group_id=i.id and r.version=?)");
      args.add(release.strip());
    }
    if (query != null && !query.isBlank()) {
      filter.append(" and position(lower(?) in lower(i.title)) > 0");
      args.add(query.strip());
    }
    String from =
        " from intelligence.incident_groups i left join lateral (select env.name as environment,r.version as release from intelligence.incident_occurrences io join intelligence.error_events e on e.id=io.event_id join intelligence.environments env on env.id=e.environment_id left join intelligence.releases r on r.id=e.release_id where io.incident_group_id=i.id order by io.occurred_at desc,io.event_id desc limit 1) latest on true";
    long total =
        jdbc.queryForObject(
            "select count(*) from intelligence.incident_groups i" + filter,
            Long.class,
            args.toArray());
    var pageArgs = new java.util.ArrayList<>(args);
    pageArgs.add(size);
    pageArgs.add((long) page * size);
    List<Summary> items =
        jdbc.query(
            "select i.id,i.title,i.status,i.assignee_id,i.occurrence_count,i.first_seen,i.last_seen,latest.environment,latest.release"
                + from
                + filter
                + " order by i.last_seen desc,i.id limit ? offset ?",
            (rs, row) -> summary(rs),
            pageArgs.toArray());
    return new Page<>(items, total, page, size);
  }

  public Detail detail(Tenant tenant, UUID incidentId) {
    Summary summary = requireIncident(tenant, incidentId);
    List<Event> events =
        jdbc.query(
            "select e.id,e.occurred_at,env.name,r.version,e.exception_type,e.message,e.stack_trace,e.request_metadata::text from intelligence.incident_occurrences io join intelligence.error_events e on e.id=io.event_id join intelligence.environments env on env.id=e.environment_id left join intelligence.releases r on r.id=e.release_id where io.incident_group_id=? order by io.occurred_at desc,io.event_id desc limit 20",
            (rs, row) ->
                new Event(
                    rs.getObject(1, UUID.class),
                    rs.getTimestamp(2).toInstant(),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getString(7),
                    rs.getString(8)),
            incidentId);
    List<Comment> comments =
        jdbc.query(
            "select recent.id,recent.author_id,recent.display_name,recent.body,recent.created_at from (select c.id,c.author_id,u.display_name,c.body,c.created_at from intelligence.incident_comments c join intelligence.users u on u.id=c.author_id where c.incident_group_id=? order by c.created_at desc,c.id desc limit 100) recent order by recent.created_at,recent.id",
            (rs, row) ->
                new Comment(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getString(3),
                    rs.getString(4),
                    rs.getTimestamp(5).toInstant()),
            incidentId);
    List<Resolution> resolutions =
        jdbc.query(
            "select r.id,r.summary,r.root_cause,r.verified_by,u.display_name,r.verified_at from intelligence.incident_resolutions r join intelligence.users u on u.id=r.verified_by where r.incident_group_id=? order by r.verified_at desc,r.id limit 50",
            (rs, row) ->
                new Resolution(
                    rs.getObject(1, UUID.class),
                    rs.getString(2),
                    rs.getString(3),
                    rs.getObject(4, UUID.class),
                    rs.getString(5),
                    rs.getTimestamp(6).toInstant()),
            incidentId);
    return new Detail(summary, events, comments, resolutions);
  }

  @Transactional
  public Summary update(Tenant tenant, UUID incidentId, Update input) {
    tenant.requireContributor();
    Summary current = requireIncident(tenant, incidentId);
    if (input.status() == Status.RESOLVED && !current.status().equals("RESOLVED"))
      throw new dev.errorintelligence.common.api.DomainException(
          org.springframework.http.HttpStatus.CONFLICT,
          "RESOLUTION_REQUIRED",
          "Use the resolution action to close an incident with a verified summary.");
    if (input.assigneeId() != null) {
      Integer membership =
          jdbc.queryForObject(
              "select count(*) from intelligence.memberships where organization_id=? and user_id=?",
              Integer.class,
              tenant.organizationId(),
              input.assigneeId());
      if (membership == null || membership == 0) throw DomainException.missing();
    }
    jdbc.update(
        "update intelligence.incident_groups set status=?,assignee_id=?,updated_at=now() where id=? and organization_id=?",
        input.status().name(),
        input.assigneeId(),
        incidentId,
        tenant.organizationId());
    audit(tenant, incidentId, "INCIDENT_UPDATED");
    return requireIncident(tenant, incidentId);
  }

  @Transactional
  public Mutation comment(Tenant tenant, UUID incidentId, CommentInput input) {
    tenant.requireContributor();
    requireIncident(tenant, incidentId);
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    jdbc.update(
        "insert into intelligence.incident_comments(id,incident_group_id,author_id,body,created_at) values(?,?,?,?,?)",
        id,
        incidentId,
        tenant.userId(),
        input.body().strip(),
        Timestamp.from(now));
    audit(tenant, incidentId, "INCIDENT_COMMENTED");
    return new Mutation(id, now);
  }

  @Transactional
  public Mutation resolve(Tenant tenant, UUID incidentId, ResolveInput input) {
    tenant.requireContributor();
    Summary incident = requireIncident(tenant, incidentId);
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    jdbc.update(
        "insert into intelligence.incident_resolutions(id,incident_group_id,summary,root_cause,verified_by,verified_at) values(?,?,?,?,?,?)",
        id,
        incidentId,
        input.summary().strip(),
        input.rootCause().strip(),
        tenant.userId(),
        Timestamp.from(now));
    UUID projectId =
        jdbc.queryForObject(
            "select project_id from intelligence.incident_groups where id=? and organization_id=?",
            UUID.class,
            incidentId,
            tenant.organizationId());
    embeddingJobs.enqueueResolution(
        tenant.organizationId(),
        projectId,
        incidentId,
        id,
        incident.title()
            + "\nVerified resolution: "
            + input.summary()
            + "\nRoot cause: "
            + input.rootCause());
    jdbc.update(
        "update intelligence.incident_groups set status='RESOLVED',updated_at=? where id=? and organization_id=?",
        Timestamp.from(now),
        incidentId,
        tenant.organizationId());
    audit(tenant, incidentId, "INCIDENT_RESOLVED");
    return new Mutation(id, now);
  }

  private void requireProject(Tenant tenant, UUID projectId) {
    Integer found =
        jdbc.queryForObject(
            "select count(*) from intelligence.projects where id=? and organization_id=?",
            Integer.class,
            projectId,
            tenant.organizationId());
    if (found == null || found == 0) throw DomainException.missing();
  }

  private Summary requireIncident(Tenant tenant, UUID id) {
    return jdbc
        .query(
            "select i.id,i.title,i.status,i.assignee_id,i.occurrence_count,i.first_seen,i.last_seen,latest.environment,latest.release from intelligence.incident_groups i left join lateral (select env.name as environment,r.version as release from intelligence.incident_occurrences io join intelligence.error_events e on e.id=io.event_id join intelligence.environments env on env.id=e.environment_id left join intelligence.releases r on r.id=e.release_id where io.incident_group_id=i.id order by io.occurred_at desc,io.event_id desc limit 1) latest on true where i.id=? and i.organization_id=?",
            (rs, row) -> summary(rs),
            id,
            tenant.organizationId())
        .stream()
        .findFirst()
        .orElseThrow(DomainException::missing);
  }

  private Summary summary(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new Summary(
        rs.getObject(1, UUID.class),
        rs.getString(2),
        rs.getString(3),
        rs.getObject(4, UUID.class),
        rs.getLong(5),
        rs.getTimestamp(6).toInstant(),
        rs.getTimestamp(7).toInstant(),
        rs.getString(8),
        rs.getString(9));
  }

  private void audit(Tenant tenant, UUID incidentId, String action) {
    jdbc.update(
        "insert into intelligence.audit_events(id,organization_id,actor_id,action,target_id) values(?,?,?,?,?)",
        UUID.randomUUID(),
        tenant.organizationId(),
        tenant.userId(),
        action,
        incidentId);
  }

  private String truncate(String value, int max) {
    String safe = value == null ? "Unknown error" : value.strip();
    return safe.length() <= max ? safe : safe.substring(0, max);
  }

  private record EventData(
      UUID id,
      UUID organizationId,
      UUID projectId,
      Instant occurredAt,
      String exceptionType,
      String message,
      String stackTrace,
      String route) {}
}

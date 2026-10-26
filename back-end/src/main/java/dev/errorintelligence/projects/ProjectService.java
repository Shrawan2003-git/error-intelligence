package dev.errorintelligence.projects;

import static dev.errorintelligence.projects.ProjectDtos.*;

import dev.errorintelligence.common.api.DomainException;
import dev.errorintelligence.common.security.Credentials;
import dev.errorintelligence.identity.AuditWriter;
import dev.errorintelligence.identity.IdentityDtos.Tenant;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {
  private final ProjectRepository projects;
  private final JdbcTemplate jdbc;
  private final AuditWriter audit;

  public ProjectService(ProjectRepository projects, JdbcTemplate jdbc, AuditWriter audit) {
    this.projects = projects;
    this.jdbc = jdbc;
    this.audit = audit;
  }

  public PageResult<Detail> list(Tenant tenant, int page, int size) {
    var result =
        projects.findByOrganizationIdAndArchivedAtIsNull(
            tenant.organizationId(),
            PageRequest.of(
                page, size, Sort.by(Sort.Order.desc("createdAt"), Sort.Order.asc("id"))));
    return new PageResult<>(
        result.stream().map(Detail::from).toList(), result.getTotalElements(), page, size);
  }

  public Project require(Tenant tenant, UUID id) {
    return projects
        .findByIdAndOrganizationId(id, tenant.organizationId())
        .orElseThrow(DomainException::missing);
  }

  @Transactional
  public Detail create(Tenant tenant, Create input) {
    tenant.requireAdmin();
    Project project =
        projects.saveAndFlush(new Project(tenant.organizationId(), input.name(), input.slug()));
    audit.record(tenant.organizationId(), tenant.userId(), "PROJECT_CREATED", project.id());
    return Detail.from(project);
  }

  @Transactional
  public Detail rename(Tenant tenant, UUID id, Rename input) {
    Project project = active(tenant, id);
    tenant.requireAdmin();
    project.rename(input.name());
    audit.record(tenant.organizationId(), tenant.userId(), "PROJECT_UPDATED", id);
    return Detail.from(project);
  }

  @Transactional
  public void archive(Tenant tenant, UUID id) {
    Project project = require(tenant, id);
    tenant.requireAdmin();
    project.archive();
    jdbc.update(
        "update intelligence.ingestion_keys set revoked_at=coalesce(revoked_at,now()) where project_id=?",
        id);
    audit.record(tenant.organizationId(), tenant.userId(), "PROJECT_ARCHIVED", id);
  }

  private Project active(Tenant tenant, UUID id) {
    Project project = require(tenant, id);
    if (project.archivedAt() != null)
      throw new DomainException(
          HttpStatus.CONFLICT, "PROJECT_ARCHIVED", "This project is archived.");
    return project;
  }

  public List<Named> environments(Tenant tenant, UUID id) {
    require(tenant, id);
    return jdbc.query(
        "select id,name from intelligence.environments where project_id=? order by name,id limit 100",
        (rs, n) -> new Named(rs.getObject(1, UUID.class), rs.getString(2)),
        id);
  }

  @Transactional
  public Named addEnvironment(Tenant tenant, UUID id, String name) {
    active(tenant, id);
    tenant.requireAdmin();
    UUID environment = UUID.randomUUID();
    jdbc.update(
        "insert into intelligence.environments(id,project_id,name) values(?,?,?)",
        environment,
        id,
        name.strip());
    audit.record(tenant.organizationId(), tenant.userId(), "ENVIRONMENT_CREATED", environment);
    return new Named(environment, name.strip());
  }

  public List<Named> releases(Tenant tenant, UUID id) {
    require(tenant, id);
    return jdbc.query(
        "select id,version from intelligence.releases where project_id=? order by created_at desc,id limit 100",
        (rs, n) -> new Named(rs.getObject(1, UUID.class), rs.getString(2)),
        id);
  }

  @Transactional
  public Named addRelease(Tenant tenant, UUID id, String version) {
    active(tenant, id);
    tenant.requireAdmin();
    UUID release = UUID.randomUUID();
    jdbc.update(
        "insert into intelligence.releases(id,project_id,version) values(?,?,?)",
        release,
        id,
        version.strip());
    audit.record(tenant.organizationId(), tenant.userId(), "RELEASE_CREATED", release);
    return new Named(release, version.strip());
  }

  public List<Key> keys(Tenant tenant, UUID id, int offset) {
    require(tenant, id);
    tenant.requireAdmin();
    return jdbc.query(
        "select id,label,prefix,scope,created_at,revoked_at from intelligence.ingestion_keys where project_id=? order by created_at desc,id limit 100 offset ?",
        (rs, n) ->
            new Key(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                rs.getString(4),
                rs.getTimestamp(5).toInstant(),
                rs.getTimestamp(6) == null ? null : rs.getTimestamp(6).toInstant()),
        id,
        offset);
  }

  @Transactional
  public CreatedKey createKey(Tenant tenant, UUID id, String label) {
    active(tenant, id);
    tenant.requireAdmin();
    UUID key = UUID.randomUUID();
    String secret = "ei_" + Credentials.randomToken();
    Instant now = Instant.now();
    String prefix = secret.substring(0, 11);
    jdbc.update(
        "insert into intelligence.ingestion_keys(id,project_id,label,digest,prefix,scope,created_at) values(?,?,?,?,?,'INGEST',?)",
        key,
        id,
        label.strip(),
        Credentials.digest(secret),
        prefix,
        Timestamp.from(now));
    audit.record(tenant.organizationId(), tenant.userId(), "KEY_CREATED", key);
    return new CreatedKey(new Key(key, label.strip(), prefix, "INGEST", now, null), secret);
  }

  @Transactional
  public void revoke(Tenant tenant, UUID id, UUID key) {
    require(tenant, id);
    tenant.requireAdmin();
    if (jdbc.update(
            "update intelligence.ingestion_keys set revoked_at=coalesce(revoked_at,now()) where id=? and project_id=?",
            key,
            id)
        == 0) throw DomainException.missing();
    audit.record(tenant.organizationId(), tenant.userId(), "KEY_REVOKED", key);
  }
}

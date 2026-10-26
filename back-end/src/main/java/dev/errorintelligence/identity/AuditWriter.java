package dev.errorintelligence.identity;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AuditWriter {
  private final JdbcTemplate jdbc;

  public AuditWriter(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public void record(UUID organizationId, UUID actorId, String action, UUID targetId) {
    jdbc.update(
        "insert into intelligence.audit_events(id,organization_id,actor_id,action,target_id) values(?,?,?,?,?)",
        UUID.randomUUID(),
        organizationId,
        actorId,
        action,
        targetId);
  }
}

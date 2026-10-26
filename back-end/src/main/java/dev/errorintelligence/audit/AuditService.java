package dev.errorintelligence.audit;

import static dev.errorintelligence.audit.AuditDtos.*;

import dev.errorintelligence.identity.IdentityDtos.Tenant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
  private final JdbcTemplate jdbc;

  public AuditService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Page recent(Tenant tenant, int limit) {
    tenant.requireAdmin();
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("Limit must be 1 to 100.");
    List<Entry> entries =
        jdbc.query(
            "select id,actor_id,action,target_id,created_at from intelligence.audit_events where organization_id=? order by created_at desc,id desc limit ?",
            (rs, row) -> entry(rs),
            tenant.organizationId(),
            limit);
    return new Page(entries, limit);
  }

  private Entry entry(ResultSet rs) throws SQLException {
    return new Entry(
        rs.getObject(1, UUID.class),
        rs.getObject(2, UUID.class),
        rs.getString(3),
        rs.getObject(4, UUID.class),
        rs.getTimestamp(5).toInstant());
  }
}

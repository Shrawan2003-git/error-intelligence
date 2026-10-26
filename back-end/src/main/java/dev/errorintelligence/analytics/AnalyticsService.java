package dev.errorintelligence.analytics;

import static dev.errorintelligence.analytics.AnalyticsDtos.*;

import dev.errorintelligence.common.api.DomainException;
import dev.errorintelligence.identity.IdentityDtos.Tenant;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {
  private final JdbcTemplate jdbc;

  public AnalyticsService(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  public Overview overview(Tenant tenant, UUID projectId, int days) {
    if (days < 7 || days > 90) throw new IllegalArgumentException("Days must be between 7 and 90.");
    Integer projectExists =
        jdbc.queryForObject(
            "select count(*) from intelligence.projects where id=? and organization_id=? and archived_at is null",
            Integer.class,
            projectId,
            tenant.organizationId());
    if (projectExists == null || projectExists == 0) throw DomainException.missing();
    long events =
        jdbc.queryForObject(
            "select count(*) from intelligence.error_events where organization_id=? and project_id=? and occurred_at>=current_date - (? * interval '1 day')",
            Long.class,
            tenant.organizationId(),
            projectId,
            days - 1);
    long incidents =
        jdbc.queryForObject(
            "select count(*) from intelligence.incident_groups where organization_id=? and project_id=? and first_seen>=current_date - (? * interval '1 day')",
            Long.class,
            tenant.organizationId(),
            projectId,
            days - 1);
    long open = countStatus(tenant.organizationId(), projectId, days, "OPEN");
    long acknowledged = countStatus(tenant.organizationId(), projectId, days, "ACKNOWLEDGED");
    long resolved = countStatus(tenant.organizationId(), projectId, days, "RESOLVED");
    List<DailyCount> daily =
        jdbc.query(
            "select d.bucket_date::date::text,coalesce(c.event_count,0) from generate_series(current_date - (? * interval '1 day'),current_date,interval '1 day') d(bucket_date) left join (select occurred_at::date as event_date,count(*) as event_count from intelligence.error_events where organization_id=? and project_id=? and occurred_at>=current_date - (? * interval '1 day') group by occurred_at::date) c on c.event_date=d.bucket_date::date order by d.bucket_date",
            (rs, row) -> new DailyCount(rs.getString(1), rs.getLong(2)),
            days - 1,
            tenant.organizationId(),
            projectId,
            days - 1);
    List<TopIncident> top =
        jdbc.query(
            "select title,occurrence_count,status from intelligence.incident_groups where organization_id=? and project_id=? and last_seen>=current_date - (? * interval '1 day') order by occurrence_count desc,last_seen desc,id limit 5",
            (rs, row) -> topIncident(rs),
            tenant.organizationId(),
            projectId,
            days - 1);
    double rate = incidents == 0 ? 0 : (double) resolved / incidents;
    return new Overview(days, events, incidents, open, acknowledged, resolved, rate, daily, top);
  }

  private long countStatus(UUID organizationId, UUID projectId, int days, String status) {
    Long value =
        jdbc.queryForObject(
            "select count(*) from intelligence.incident_groups where organization_id=? and project_id=? and status=? and first_seen>=current_date - (? * interval '1 day')",
            Long.class,
            organizationId,
            projectId,
            status,
            days - 1);
    return value == null ? 0 : value;
  }

  private TopIncident topIncident(ResultSet rs) throws SQLException {
    return new TopIncident(rs.getString(1), rs.getLong(2), rs.getString(3));
  }
}

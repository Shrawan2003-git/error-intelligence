package dev.errorintelligence.operations;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deletes expired raw events in bounded batches while retaining incident aggregates and history.
 */
@Component
public class EventRetentionJob {
  private final JdbcTemplate jdbc;
  private final int retentionDays;
  private final Counter deletedEvents;

  public EventRetentionJob(
      JdbcTemplate jdbc,
      MeterRegistry meters,
      @Value("${app.retention.raw-event-days:0}") int retentionDays) {
    this.jdbc = jdbc;
    this.retentionDays = retentionDays;
    this.deletedEvents = meters.counter("error_intelligence_retention_deleted_events_total");
    if (retentionDays < 0) throw new IllegalArgumentException("Retention days cannot be negative.");
  }

  @Scheduled(fixedDelayString = "${app.retention.interval:PT1M}")
  @Transactional(timeout = 10)
  public void scheduledPurge() {
    long deadline = System.nanoTime() + java.time.Duration.ofSeconds(5).toNanos();
    for (int batch = 0; batch < 20 && System.nanoTime() < deadline; batch++) {
      if (purgeExpiredBatch() < 500) break;
    }
  }

  @Transactional
  public int purgeExpiredBatch() {
    if (retentionDays == 0) return 0;
    Timestamp cutoff = Timestamp.from(Instant.now().minus(retentionDays, ChronoUnit.DAYS));
    List<UUID> eventIds =
        jdbc.query(
            "select id from intelligence.error_events where occurred_at<? order by occurred_at,id limit 500 for update skip locked",
            (rs, row) -> rs.getObject(1, UUID.class),
            cutoff);
    if (eventIds.isEmpty()) return 0;
    String placeholders = String.join(",", java.util.Collections.nCopies(eventIds.size(), "?"));
    Object[] ids = eventIds.toArray();
    jdbc.update(
        "delete from intelligence.incident_occurrences where event_id in (" + placeholders + ")",
        ids);
    jdbc.update(
        "delete from intelligence.error_fingerprints where event_id in (" + placeholders + ")",
        ids);
    int deleted =
        jdbc.update(
            "delete from intelligence.error_events where id in (" + placeholders + ")", ids);
    deletedEvents.increment(deleted);
    return deleted;
  }
}

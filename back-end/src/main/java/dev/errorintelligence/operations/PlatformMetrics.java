package dev.errorintelligence.operations;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scrapes read a cached snapshot; only the bounded collector accesses the database. */
@Component
public class PlatformMetrics {
  private final JdbcTemplate jdbc;
  private volatile Map<String, Double> snapshot = Map.of();
  private volatile double lastSuccess;
  private volatile double collectionFailure;

  public PlatformMetrics(MeterRegistry meters, JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    for (String metric :
        new String[] {
          "events_stored",
          "incidents_open",
          "ai_jobs_queued",
          "embedding_jobs_queued",
          "ai_oldest_job_seconds",
          "embedding_oldest_job_seconds",
          "retention_oldest_event_seconds"
        }) {
      Gauge.builder(
              "error_intelligence_" + metric,
              this,
              state -> state.snapshot.getOrDefault(metric, Double.NaN))
          .register(meters);
    }
    Gauge.builder(
            "error_intelligence_metrics_last_success_seconds", this, state -> state.lastSuccess)
        .register(meters);
    Gauge.builder(
            "error_intelligence_metrics_collection_failure", this, state -> state.collectionFailure)
        .register(meters);
  }

  @Scheduled(fixedDelayString = "${app.observability.metrics-interval:PT30S}")
  @org.springframework.transaction.annotation.Transactional(readOnly = true, timeout = 5)
  public void collect() {
    try {
      Map<String, Double> next =
          Map.of(
              "events_stored", number("select count(*) from intelligence.error_events"),
              "incidents_open",
                  number("select count(*) from intelligence.incident_groups where status='OPEN'"),
              "ai_jobs_queued",
                  number(
                      "select count(*) from intelligence.incident_ai_analyses where status in ('QUEUED','RETRYING')"),
              "embedding_jobs_queued",
                  number(
                      "select count(*) from intelligence.embedding_jobs where status in ('QUEUED','RETRYING')"),
              "ai_oldest_job_seconds",
                  number(
                      "select coalesce(extract(epoch from now()-min(created_at)),0) from intelligence.incident_ai_analyses where status in ('QUEUED','RUNNING','RETRYING')"),
              "embedding_oldest_job_seconds",
                  number(
                      "select coalesce(extract(epoch from now()-min(created_at)),0) from intelligence.embedding_jobs where status in ('QUEUED','RUNNING','RETRYING')"),
              "retention_oldest_event_seconds",
                  number(
                      "select coalesce(extract(epoch from now()-min(occurred_at)),0) from intelligence.error_events"));
      snapshot = next;
      lastSuccess = System.currentTimeMillis() / 1000.0;
      collectionFailure = 0;
    } catch (RuntimeException ignored) {
      collectionFailure = 1;
    }
  }

  private double number(String sql) {
    Number value = jdbc.queryForObject(sql, Number.class);
    return value == null ? 0 : value.doubleValue();
  }
}

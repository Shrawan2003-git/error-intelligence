package dev.errorintelligence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;

final class MigrationChecks {
  private MigrationChecks() {}

  static void verifyUpgrade(String url, String username, String password) throws Exception {
    Flyway baseline =
        Flyway.configure()
            .dataSource(url, username, password)
            .locations("classpath:db/migration")
            .target("1")
            .load();
    assertThat(baseline.migrate().migrationsExecuted).isEqualTo(1);
    assertThat(baseline.migrate().migrationsExecuted).isZero();
    try (var connection = DriverManager.getConnection(url, username, password);
        var statement = connection.createStatement()) {
      statement.execute(
          "create table intelligence.upgrade_probe (id integer primary key, payload text not null)");
      statement.execute("insert into intelligence.upgrade_probe values (1, 'preserve me')");
      Flyway.configure()
          .dataSource(url, username, password)
          .locations("classpath:db/migration")
          .target("7")
          .load()
          .migrate();
      statement.execute(
          Files.readString(Path.of("src/test/resources/db/upgrade-business-fixture.sql")));
      Flyway upgrade =
          Flyway.configure()
              .dataSource(url, username, password)
              .locations("classpath:db/migration", "classpath:db/test-upgrade")
              .load();
      assertThat(upgrade.migrate().migrationsExecuted).isPositive();
      upgrade.validate();
      assertThat(upgrade.migrate().migrationsExecuted).isZero();
      try (var result =
          statement.executeQuery(
              """
          SELECT e.message, r.event_id = e.id AS receipt_matches,
                 j.source_text, j.source_version, j.claim_token, g.occurrence_count
          FROM intelligence.error_events e
          JOIN intelligence.ingestion_receipts r ON r.project_id = e.project_id
            AND r.client_event_id = e.client_event_id
          JOIN intelligence.embedding_jobs j ON j.project_id = e.project_id
          JOIN intelligence.incident_groups g ON g.id = j.incident_group_id
          WHERE e.message = 'Synthetic upgrade evidence'
          """)) {
        assertThat(result.next()).isTrue();
        assertThat(result.getBoolean("receipt_matches")).isTrue();
        assertThat(result.getString("source_text")).isEqualTo("Synthetic upgrade evidence");
        assertThat(result.getLong("source_version")).isEqualTo(1);
        assertThat(result.getObject("claim_token")).isNull();
        assertThat(result.getLong("occurrence_count")).isEqualTo(1);
        assertThat(result.next()).isFalse();
      }
      try (var result =
          statement.executeQuery(
              "select payload, marker from intelligence.upgrade_probe where id = 1")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString("payload")).isEqualTo("preserve me");
        assertThat(result.getString("marker")).isEqualTo("upgraded");
      }
    }
  }
}

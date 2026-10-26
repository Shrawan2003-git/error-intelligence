package dev.errorintelligence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;

class NativeFlywayMigrationIT {
  @Test
  void baselineIsRepeatableAndAdditiveUpgradePreservesExistingData() throws Exception {
    try (NativePostgres postgres = NativePostgres.start("migration")) {
      MigrationChecks.verifyUpgrade(postgres.url(), postgres.username(), postgres.password());
    }
  }

  @Test
  void nativePostgresLoadsProjectLocalPgvectorRuntime() throws Exception {
    try (NativePostgres postgres = NativePostgres.start("pgvector");
        var connection =
            DriverManager.getConnection(postgres.url(), postgres.username(), postgres.password());
        var statement = connection.createStatement()) {
      statement.execute("CREATE EXTENSION vector");
      try (var result = statement.executeQuery("SELECT '[1,0]'::vector <=> '[0,1]'::vector")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getDouble(1)).isEqualTo(1.0);
      }
    }
  }
}

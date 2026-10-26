package dev.errorintelligence;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class FlywayMigrationIT {
  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer(TestImages.POSTGRES);

  @Test
  void baselineIsRepeatableAndAdditiveUpgradePreservesExistingData() throws Exception {
    MigrationChecks.verifyUpgrade(
        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
  }
}

package dev.errorintelligence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** Restores a custom-format dump only into a new database in this disposable test cluster. */
@ActiveProfiles("native")
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@TestPropertySource(
    properties = {"app.ai.worker.enabled=false", "app.ai.embedding-worker.enabled=false"})
class NativeBackupRestoreIT {
  private static final NativePostgres POSTGRES = NativePostgres.start("backup-restore");

  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::url);
    registry.add("spring.datasource.username", POSTGRES::username);
    registry.add("spring.datasource.password", POSTGRES::password);
  }

  @AfterAll
  static void shutdown() throws Exception {
    POSTGRES.close();
  }

  @Test
  void customDumpRestoresMigratedSchemaAndDataIntoAnEmptyDatabase() throws Exception {
    UUID marker = UUID.randomUUID();
    jdbc.update(
        "insert into intelligence.organizations(id,name) values(?,?)",
        marker,
        "restore-drill-marker");
    jdbc.execute("create database restore_drill");
    String[] connection = POSTGRES.url().replace("jdbc:postgresql://", "").split("/", 2);
    String[] hostAndPort = connection[0].split(":", 2);
    Path backup = Files.createTempFile("error-intelligence-restore-drill-", ".dump");
    try {
      runPostgresTool(
          "pg_dump",
          "-Fc",
          "--no-owner",
          "-h",
          hostAndPort[0],
          "-p",
          hostAndPort[1],
          "-U",
          POSTGRES.username(),
          "-d",
          "postgres",
          "-f",
          backup.toString());
      runPostgresTool(
          "pg_restore",
          "--exit-on-error",
          "--no-owner",
          "-h",
          hostAndPort[0],
          "-p",
          hostAndPort[1],
          "-U",
          POSTGRES.username(),
          "-d",
          "restore_drill",
          backup.toString());

      try (var restored =
              DriverManager.getConnection(
                  POSTGRES.url().replace("/postgres", "/restore_drill"),
                  POSTGRES.username(),
                  POSTGRES.password());
          var statement = restored.createStatement();
          var result =
              statement.executeQuery(
                  "select name from intelligence.organizations where id='" + marker + "'")) {
        assertThat(result.next()).isTrue();
        assertThat(result.getString(1)).isEqualTo("restore-drill-marker");
      }
    } finally {
      Files.deleteIfExists(backup);
    }
  }

  private void runPostgresTool(String tool, String... arguments) throws Exception {
    Path bin = Path.of(System.getProperty("native.pg.bin"));
    String suffix = System.getProperty("os.name").startsWith("Windows") ? ".exe" : "";
    var command = new java.util.ArrayList<String>();
    command.add(bin.resolve(tool + suffix).toString());
    command.addAll(java.util.List.of(arguments));
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    builder.environment().put("PGPASSWORD", POSTGRES.password());
    Process process = builder.start();
    byte[] output = process.getInputStream().readAllBytes();
    if (!process.waitFor(45, java.util.concurrent.TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException(tool + " timed out during the isolated restore drill.");
    }
    if (process.exitValue() != 0)
      throw new IllegalStateException(
          tool + " failed during the isolated restore drill: " + new String(output));
  }
}

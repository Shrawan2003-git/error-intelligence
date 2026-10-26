package dev.errorintelligence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

/** Explicit -Dit.test=NativeBrowserIT acceptance against a disposable real application. */
@ActiveProfiles("native")
@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(
    properties = {"app.ai.worker.enabled=false", "app.ai.embedding-worker.enabled=false"})
class NativeBrowserIT {
  static final NativePostgres postgres = NativePostgres.start("browser");
  @LocalServerPort int port;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::url);
    registry.add("spring.datasource.username", postgres::username);
    registry.add("spring.datasource.password", postgres::password);
  }

  @AfterAll
  static void shutdown() throws Exception {
    postgres.close();
  }

  @Test
  void browserUsesTheRealApiAndDatabase() throws Exception {
    Path frontend = Path.of("../front-end").toAbsolutePath().normalize();
    Path log = Path.of("target/live-browser.log").toAbsolutePath();
    boolean windows = System.getProperty("os.name").startsWith("Windows");
    ProcessBuilder builder =
        new ProcessBuilder(
            windows
                ? new String[] {
                  "cmd.exe",
                  "/c",
                  "npm",
                  "run",
                  "test:e2e",
                  "--",
                  "e2e/live-flow.spec.ts",
                  "--project=desktop",
                  "--workers=1"
                }
                : new String[] {
                  "npm",
                  "run",
                  "test:e2e",
                  "--",
                  "e2e/live-flow.spec.ts",
                  "--project=desktop",
                  "--workers=1"
                });
    builder.directory(frontend.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
    builder.environment().remove("E2E_BASE_URL");
    builder.environment().put("E2E_LIVE", "true");
    builder.environment().put("E2E_REQUIRE_READY", "true");
    builder.environment().put("FRONTEND_PORT", "5183");
    builder.environment().put("API_PROXY_TARGET", "http://127.0.0.1:" + port);
    Process process = builder.start();
    try {
      assertThat(process.waitFor(Duration.ofMinutes(3).toMillis(), TimeUnit.MILLISECONDS))
          .as("Browser runner timed out; see %s", log)
          .isTrue();
      assertThat(process.exitValue()).as("Live browser failed: %s", Files.readString(log)).isZero();
    } finally {
      if (process.isAlive()) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
    }
  }
}

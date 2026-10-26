package dev.errorintelligence;

import org.junit.jupiter.api.AfterAll;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("native")
class NativeIncidentIT extends IncidentContract {
  static final NativePostgres postgres = NativePostgres.start("incidents");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", postgres::url);
    registry.add("spring.datasource.username", postgres::username);
    registry.add("spring.datasource.password", postgres::password);
    registry.add("app.ai.worker.enabled", () -> "false");
  }

  @AfterAll
  static void shutdown() throws Exception {
    postgres.close();
  }
}

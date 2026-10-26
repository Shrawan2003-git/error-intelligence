package dev.errorintelligence;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@ActiveProfiles("native")
class NativeFoundationIT extends FoundationContract {
  static final NativePostgres postgres = NativePostgres.start("foundation");

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

  @Test
  void databaseOutageMakesReadinessFailButDoesNotKillLiveness() throws Exception {
    postgres.stop();
    try {
      mvc.perform(get("/actuator/health/readiness"))
          .andExpect(status().isServiceUnavailable())
          .andExpect(jsonPath("$.status").value("DOWN"))
          .andExpect(jsonPath("$.components").doesNotExist());
      mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
    } finally {
      postgres.restart();
    }
  }

  @Test
  void loopbackNativeModeUsesSingleProcessAuthenticationLimits() throws Exception {
    mvc.perform(get("/api/v1/system")).andExpect(jsonPath("$.cacheRequired").value(false));
  }
}

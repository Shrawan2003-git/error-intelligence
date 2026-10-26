package dev.errorintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {"app.ai.worker.enabled=false", "app.ai.embedding-worker.enabled=false"})
abstract class FoundationContract {

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @Autowired
  @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
  org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping mappings;

  @Test
  void openApiDescribesEveryBusinessRouteAndMethod() throws Exception {
    Map<String, Object> spec;
    try (var input =
        java.nio.file.Files.newInputStream(java.nio.file.Path.of("../docs/openapi.yaml"))) {
      spec =
          new org.yaml.snakeyaml.Yaml(
                  new org.yaml.snakeyaml.constructor.SafeConstructor(
                      new org.yaml.snakeyaml.LoaderOptions()))
              .load(input);
    }
    @SuppressWarnings("unchecked")
    var paths = (java.util.Map<String, java.util.Map<String, Object>>) spec.get("paths");
    mappings
        .getHandlerMethods()
        .forEach(
            (mapping, handler) -> {
              if (!handler.getBeanType().getPackageName().startsWith("dev.errorintelligence"))
                return;
              for (String route : mapping.getPatternValues()) {
                assertThat(paths).as("Undocumented route %s", route).containsKey(route);
                for (var method : mapping.getMethodsCondition().getMethods()) {
                  assertThat(paths.get(route))
                      .as("Undocumented method %s %s", method, route)
                      .containsKey(method.name().toLowerCase(java.util.Locale.ROOT));
                }
              }
            });
  }

  @Test
  void systemMetadataIsPublicAndContainsNoInfrastructureDetails() throws Exception {
    mvc.perform(get("/api/v1/system"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Error Intelligence"))
        .andExpect(jsonPath("$.phase").value("ANALYTICS_AND_OPERATIONS"))
        .andExpect(jsonPath("$.password").doesNotExist())
        .andExpect(header().exists("X-Trace-Id"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"));
  }

  @Test
  void readinessChecksRequiredDependenciesWithoutExposingDetails() throws Exception {
    mvc.perform(get("/actuator/health/readiness"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("UP"))
        .andExpect(jsonPath("$.components").doesNotExist());
    mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
  }

  @Test
  void prometheusScrapeIsAvailableToLoopbackAndContainsOperationalGauges() throws Exception {
    mvc.perform(get("/actuator/prometheus"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.containsString("error_intelligence_incidents_open")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.containsString("http_server_requests_seconds_count")));
  }

  @Test
  void unknownAndSensitiveRoutesAreDeniedWithProblemResponse() throws Exception {
    for (String path :
        new String[] {"/api/v1/projects", "/actuator/env", "/actuator/configprops"}) {
      var response =
          mvc.perform(get(path))
              .andExpect(status().isUnauthorized())
              .andExpect(content().contentType("application/problem+json"))
              .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
              .andExpect(jsonPath("$.traceId").isNotEmpty())
              .andReturn()
              .getResponse();
      assertThat(response.getContentAsString()).doesNotContain("stackTrace", "password", "jdbc:");
    }
  }

  @Test
  void unsafeRequestsCannotBypassSecurity() throws Exception {
    mvc.perform(post("/api/v1/system"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
  }

  @Test
  void baselineMigrationHasRunAgainstRealPostgres() {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.schemata where schema_name = 'intelligence'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version = '5' and success",
                Integer.class))
        .isEqualTo(1);
  }
}

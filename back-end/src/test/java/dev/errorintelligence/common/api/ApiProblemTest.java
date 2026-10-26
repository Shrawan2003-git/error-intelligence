package dev.errorintelligence.common.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

class ApiProblemTest {
  @Test
  void carriesStableCodeAndServerTraceWithoutInternalDetails() {
    MDC.put("traceId", "test-trace");
    try {
      var problem =
          ApiProblem.create(
              HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required.");
      assertThat(problem.getStatus()).isEqualTo(401);
      assertThat(problem.getProperties())
          .containsEntry("code", "AUTHENTICATION_REQUIRED")
          .containsEntry("traceId", "test-trace");
      assertThat(problem.getType().toString())
          .isEqualTo("urn:error-intelligence:problem:authentication-required");
    } finally {
      MDC.clear();
    }
  }
}

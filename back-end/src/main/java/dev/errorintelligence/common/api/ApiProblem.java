package dev.errorintelligence.common.api;

import java.net.URI;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

public final class ApiProblem {
  private ApiProblem() {}

  public static ProblemDetail create(HttpStatus status, String code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(
        URI.create("urn:error-intelligence:problem:" + code.toLowerCase().replace('_', '-')));
    problem.setTitle(status.getReasonPhrase());
    problem.setProperty("code", code);
    problem.setProperty("traceId", MDC.get("traceId"));
    return problem;
  }
}

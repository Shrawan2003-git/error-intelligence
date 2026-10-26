package dev.errorintelligence.common.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(DomainException.class)
  ProblemDetail domain(DomainException exception) {
    return ApiProblem.create(exception.status(), exception.code(), exception.getMessage());
  }

  @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
  ProblemDetail conflict() {
    return ApiProblem.create(
        HttpStatus.CONFLICT,
        "CONFLICT",
        "The resource already exists or conflicts with an existing record.");
  }

  @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
  ProblemDetail denied() {
    return ApiProblem.create(
        HttpStatus.FORBIDDEN,
        "ACCESS_DENIED",
        "You do not have permission to perform this action.");
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(Exception exception) {
    // Do not log exception messages: future payloads may contain credentials or personal data.
    log.error("Unhandled request failure; exceptionType={}", exception.getClass().getSimpleName());
    return ApiProblem.create(
        HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "The request could not be completed.");
  }
}

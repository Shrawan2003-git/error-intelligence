package dev.errorintelligence.common.api;

import org.springframework.http.HttpStatus;

public class DomainException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public DomainException(HttpStatus status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }

  public static DomainException missing() {
    return new DomainException(HttpStatus.NOT_FOUND, "NOT_FOUND", "The resource was not found.");
  }

  public static DomainException denied() {
    return new DomainException(
        HttpStatus.FORBIDDEN,
        "ACCESS_DENIED",
        "You do not have permission to perform this action.");
  }

  public static DomainException unauthenticated() {
    return new DomainException(
        HttpStatus.UNAUTHORIZED, "INVALID_SESSION", "Your session has expired. Sign in again.");
  }
}

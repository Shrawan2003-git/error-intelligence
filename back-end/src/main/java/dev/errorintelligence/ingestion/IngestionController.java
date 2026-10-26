package dev.errorintelligence.ingestion;

import static dev.errorintelligence.ingestion.IngestionDtos.*;

import dev.errorintelligence.identity.AuthRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IngestionController {
  private final IngestionService service;
  private final AuthRateLimiter limiter;

  public IngestionController(IngestionService service, AuthRateLimiter limiter) {
    this.service = service;
    this.limiter = limiter;
  }

  @PostMapping("/ingest/v1/events")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Receipt ingest(
      @RequestHeader(name = "X-API-Key", required = false) String key,
      @Valid @RequestBody EventInput input,
      HttpServletRequest request,
      HttpServletResponse response) {
    limiter.check("ingest-ip", request.getRemoteAddr(), 1000);
    if (key != null) limiter.check("ingest-key", key, 600);
    Receipt result = service.accept(key, input);
    response.setHeader("Cache-Control", "no-store");
    return result;
  }
}

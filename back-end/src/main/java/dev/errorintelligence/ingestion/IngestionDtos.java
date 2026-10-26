package dev.errorintelligence.ingestion;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class IngestionDtos {
  private IngestionDtos() {}

  public record EventInput(
      @NotNull UUID eventId,
      @NotNull Instant occurredAt,
      @NotBlank @Size(max = 80) String environment,
      @Size(max = 120) String release,
      @NotBlank @Size(max = 500) String exceptionType,
      @NotBlank @Size(max = 4000) String message,
      @Size(max = 12000) String stackTrace,
      @Valid RequestContext request,
      @Size(max = 40) Map<@Size(max = 100) String, @Size(max = 2000) String> environmentValues) {}

  public record RequestContext(
      @Size(max = 16) String method,
      @Size(max = 2048) String path,
      @Size(max = 40) Map<@Size(max = 100) String, @Size(max = 1000) String> headers,
      @Size(max = 40) Map<@Size(max = 100) String, @Size(max = 1000) String> query,
      @Size(max = 4000) String bodyExcerpt) {}

  public record Receipt(UUID eventId, UUID projectId, boolean duplicate, Instant acceptedAt) {}
}

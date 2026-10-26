package dev.errorintelligence.incidents;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class IncidentDtos {
  private IncidentDtos() {}

  public record Page<T>(List<T> items, long total, int page, int size) {}

  public record Summary(
      UUID id,
      String title,
      String status,
      UUID assigneeId,
      long occurrenceCount,
      Instant firstSeen,
      Instant lastSeen,
      String environment,
      String release) {}

  public record Event(
      UUID id,
      Instant occurredAt,
      String environment,
      String release,
      String exceptionType,
      String message,
      String stackTrace,
      String requestMetadata) {}

  public record Comment(
      UUID id, UUID authorId, String authorName, String body, Instant createdAt) {}

  public record Resolution(
      UUID id,
      String summary,
      String rootCause,
      UUID verifiedBy,
      String verifierName,
      Instant verifiedAt) {}

  public record Detail(
      Summary incident,
      List<Event> recentEvents,
      List<Comment> comments,
      List<Resolution> resolutions) {}

  public record Update(@NotNull Status status, UUID assigneeId) {}

  public record CommentInput(@NotBlank @Size(max = 4000) String body) {}

  public record ResolveInput(
      @NotBlank @Size(max = 500) String summary, @NotBlank @Size(max = 4000) String rootCause) {}

  public record Mutation(UUID id, Instant createdAt) {}

  public enum Status {
    OPEN,
    ACKNOWLEDGED,
    RESOLVED
  }
}

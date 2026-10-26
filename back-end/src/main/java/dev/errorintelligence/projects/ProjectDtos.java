package dev.errorintelligence.projects;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ProjectDtos {
  private ProjectDtos() {}

  public record Create(
      @NotBlank @Size(max = 120) String name,
      @NotBlank @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") @Size(max = 80) String slug) {}

  public record Rename(@NotBlank @Size(max = 120) String name) {}

  public record NamedInput(@NotBlank @Size(max = 80) String name) {}

  public record ReleaseInput(@NotBlank @Size(max = 120) String version) {}

  public record KeyInput(@NotBlank @Size(max = 100) String label) {}

  public record Detail(UUID id, String name, String slug, Instant createdAt, Instant archivedAt) {
    public static Detail from(Project project) {
      return new Detail(
          project.id(), project.name(), project.slug(), project.createdAt(), project.archivedAt());
    }
  }

  public record PageResult<T>(List<T> items, long total, int page, int size) {}

  public record Named(UUID id, String name) {}

  public record Key(
      UUID id, String label, String prefix, String scope, Instant createdAt, Instant revokedAt) {}

  public record CreatedKey(Key metadata, String secret) {}
}

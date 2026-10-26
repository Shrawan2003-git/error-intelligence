package dev.errorintelligence.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AuditDtos {
  private AuditDtos() {}

  public record Page(List<Entry> items, int limit) {}

  public record Entry(UUID id, UUID actorId, String action, UUID targetId, Instant createdAt) {}
}

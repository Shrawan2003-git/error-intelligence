package dev.errorintelligence.projects;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "projects", schema = "intelligence")
public class Project {
  @Id private UUID id;

  @Column(name = "organization_id", nullable = false)
  private UUID organizationId;

  @Column(nullable = false, length = 120)
  private String name;

  @Column(nullable = false, length = 80)
  private String slug;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "archived_at")
  private Instant archivedAt;

  protected Project() {}

  public Project(UUID organizationId, String name, String slug) {
    this.id = UUID.randomUUID();
    this.organizationId = organizationId;
    this.name = name.strip();
    this.slug = slug;
    this.createdAt = Instant.now();
  }

  public UUID id() {
    return id;
  }

  public UUID organizationId() {
    return organizationId;
  }

  public String name() {
    return name;
  }

  public String slug() {
    return slug;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant archivedAt() {
    return archivedAt;
  }

  public void rename(String name) {
    this.name = name.strip();
  }

  public void archive() {
    this.archivedAt = Instant.now();
  }
}

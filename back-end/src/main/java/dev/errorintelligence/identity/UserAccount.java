package dev.errorintelligence.identity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users", schema = "intelligence")
public class UserAccount {
  @Id private UUID id;

  @Column(nullable = false, unique = true, length = 254)
  private String email;

  @Column(name = "display_name", nullable = false, length = 100)
  private String displayName;

  @Column(name = "password_hash", nullable = false, length = 100)
  private String passwordHash;

  @Column(name = "email_verified_at")
  private Instant emailVerifiedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected UserAccount() {}

  public UserAccount(String email, String displayName, String hash) {
    this.id = UUID.randomUUID();
    this.email = email;
    this.displayName = displayName;
    this.passwordHash = hash;
    this.createdAt = Instant.now();
  }

  public UUID id() {
    return id;
  }

  public String email() {
    return email;
  }

  public String displayName() {
    return displayName;
  }

  public String passwordHash() {
    return passwordHash;
  }

  public Instant emailVerifiedAt() {
    return emailVerifiedAt;
  }

  public void verifyEmail(Instant at) {
    emailVerifiedAt = at;
  }

  public void changePasswordHash(String hash) {
    passwordHash = hash;
  }
}

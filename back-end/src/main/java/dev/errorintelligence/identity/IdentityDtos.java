package dev.errorintelligence.identity;

import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public final class IdentityDtos {
  private IdentityDtos() {}

  public record Registration(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 12, max = 64) String password,
      @NotBlank @Size(max = 100) String displayName,
      @NotBlank @Size(max = 120) String organizationName) {}

  public record Login(
      @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 64) String password) {}

  public record EmailInput(@NotBlank @Email @Size(max = 254) String email) {}

  public record TokenInput(@NotBlank @Size(max = 128) String token) {}

  public record PasswordResetInput(
      @NotBlank @Size(max = 128) String token,
      @NotBlank @Size(min = 12, max = 64) String newPassword) {}

  public record OrganizationInput(@NotBlank @Size(max = 120) String name) {}

  public record SwitchOrganization(@NotNull UUID organizationId) {}

  public record MemberInput(@NotBlank @Email @Size(max = 254) String email, @NotNull Role role) {}

  public record RoleInput(@NotNull Role role) {}

  public record Organization(UUID id, String name, Role role) {}

  public record Member(UUID userId, String displayName, String email, Role role) {}

  public record Account(
      UUID id,
      String email,
      String displayName,
      List<Organization> organizations,
      UUID activeOrganizationId) {}

  public record SessionResponse(String accessToken, long expiresIn, Account account) {}

  public record IssuedSession(SessionResponse response, String refreshToken) {}

  public record Tenant(UUID userId, UUID sessionId, UUID organizationId, Role role) {
    public void requireAdmin() {
      if (role != Role.OWNER && role != Role.ADMIN)
        throw dev.errorintelligence.common.api.DomainException.denied();
    }

    public void requireContributor() {
      if (role == Role.VIEWER) throw dev.errorintelligence.common.api.DomainException.denied();
    }
  }

  public enum Role {
    OWNER,
    ADMIN,
    DEVELOPER,
    VIEWER
  }
}

package dev.errorintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;

@org.springframework.test.annotation.DirtiesContext(
    classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest
@AutoConfigureMockMvc
@org.springframework.test.context.TestPropertySource(
    properties = {"app.ai.worker.enabled=false", "app.ai.embedding-worker.enabled=false"})
abstract class IdentityContract {
  @Autowired org.springframework.test.web.servlet.MockMvc mvc;
  @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired dev.errorintelligence.identity.AuthMailOutbox mail;

  @Test
  void verifiedRegistrationCreatesOwnerOrganizationAndRotatingHashedRefreshSession()
      throws Exception {
    var registration = register("owner@example.test", "Owner Org");
    JsonNode body = mapper.readTree(registration.getResponse().getContentAsString());
    String access = body.path("accessToken").asText();
    String organization = body.path("account").path("activeOrganizationId").asText();
    String refresh = registration.getResponse().getCookie("EI_REFRESH").getValue();

    assertThat(access).isNotBlank();
    assertThat(refresh).hasSize(43);
    assertThat(
            registration.getResponse().getHeaders("Set-Cookie").stream()
                .filter(value -> value.startsWith("EI_REFRESH="))
                .findFirst()
                .orElseThrow())
        .contains("HttpOnly", "SameSite=Strict", "Path=/api/v1/auth");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from intelligence.users where email='owner@example.test'",
                Integer.class))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from intelligence.refresh_tokens where digest=? and consumed_at is null",
                Integer.class,
                dev.errorintelligence.common.security.Credentials.digest(refresh)))
        .isEqualTo(1);

    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.activeOrganizationId").value(organization))
        .andExpect(jsonPath("$.email").value("owner@example.test"));

    var rotated =
        mvc.perform(
                post("/api/v1/auth/refresh")
                    .with(csrf())
                    .cookie(registration.getResponse().getCookie("EI_REFRESH")))
            .andExpect(status().isOk())
            .andReturn();
    String newAccess =
        mapper.readTree(rotated.getResponse().getContentAsString()).path("accessToken").asText();
    var rotatedCookie = rotated.getResponse().getCookie("EI_REFRESH");
    assertThat(rotatedCookie).isNotNull();
    assertThat(rotatedCookie.getValue()).isNotEqualTo(refresh);

    mvc.perform(
            post("/api/v1/auth/refresh")
                .with(csrf())
                .cookie(registration.getResponse().getCookie("EI_REFRESH")))
        .andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + newAccess))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void newAccountCannotSignInUntilEmailIsVerified() throws Exception {
    registerPending("pending@example.test", "Pending Org");
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"pending@example.test\",\"password\":\"a-strong-password-123\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("EMAIL_NOT_VERIFIED"));
    String token = TestAuthMail.token(jdbc, mail, "pending@example.test", "VERIFY_EMAIL");
    TestAuthMail.verify(mvc, jdbc, mail, "pending@example.test");
    mvc.perform(
            post("/api/v1/auth/verification/confirm")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
  }

  @Test
  void verificationResendInvalidatesTheEarlierLink() throws Exception {
    registerPending("resend@example.test", "Resend Org");
    String first = TestAuthMail.token(jdbc, mail, "resend@example.test", "VERIFY_EMAIL");
    mvc.perform(
            post("/api/v1/auth/verification/resend")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"resend@example.test\"}"))
        .andExpect(status().isAccepted());
    mvc.perform(
            post("/api/v1/auth/verification/confirm")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + first + "\"}"))
        .andExpect(status().isBadRequest());
    TestAuthMail.verify(mvc, jdbc, mail, "resend@example.test");
  }

  @Test
  void expiredVerificationLinkCannotActivateAnAccount() throws Exception {
    registerPending("expired@example.test", "Expired Org");
    String token = TestAuthMail.token(jdbc, mail, "expired@example.test", "VERIFY_EMAIL");
    jdbc.update(
        "update intelligence.auth_challenges set expires_at=now()-interval '1 second'"
            + " where digest=?",
        dev.errorintelligence.common.security.Credentials.digest(token));
    mvc.perform(
            post("/api/v1/auth/verification/confirm")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"expired@example.test\",\"password\":\"a-strong-password-123\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void passwordResetIsSingleUseAndRevokesExistingSessions() throws Exception {
    var signedIn = register("reset@example.test", "Reset Org");
    String oldAccess =
        mapper.readTree(signedIn.getResponse().getContentAsString()).path("accessToken").asText();
    mvc.perform(
            post("/api/v1/auth/password/forgot")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"reset@example.test\"}"))
        .andExpect(status().isAccepted());
    String token = TestAuthMail.token(jdbc, mail, "reset@example.test", "RESET_PASSWORD");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from intelligence.auth_challenges where digest=?",
                Integer.class,
                dev.errorintelligence.common.security.Credentials.digest(token)))
        .isEqualTo(1);
    mvc.perform(
            post("/api/v1/auth/password/reset")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"token\":\"" + token + "\",\"newPassword\":\"new-strong-password-456\"}"))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + oldAccess))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/password/reset")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"token\":\""
                        + token
                        + "\",\"newPassword\":\"another-strong-password-789\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"reset@example.test\",\"password\":\"a-strong-password-123\"}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"reset@example.test\",\"password\":\"new-strong-password-456\"}"))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/auth/password/forgot")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"unknown@example.test\"}"))
        .andExpect(status().isAccepted());
  }

  @Test
  void projectAccessIsRoleCheckedAndTenantScoped() throws Exception {
    var owner = register("admin@example.test", "Admin Org");
    JsonNode ownerBody = mapper.readTree(owner.getResponse().getContentAsString());
    String ownerAccess = ownerBody.path("accessToken").asText();
    String organizationId = ownerBody.path("account").path("activeOrganizationId").asText();

    var project =
        mvc.perform(
                post("/api/v1/projects")
                    .with(csrf())
                    .header("Authorization", "Bearer " + ownerAccess)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"Payments\",\"slug\":\"payments\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.slug").value("payments"))
            .andReturn();
    String projectId =
        mapper.readTree(project.getResponse().getContentAsString()).path("id").asText();

    var viewer = register("viewer@example.test", "Viewer Org");
    String viewerAccess =
        mapper.readTree(viewer.getResponse().getContentAsString()).path("accessToken").asText();
    mvc.perform(
            post("/api/v1/organizations/{id}/members", organizationId)
                .with(csrf())
                .header("Authorization", "Bearer " + ownerAccess)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"viewer@example.test\",\"role\":\"VIEWER\"}"))
        .andExpect(status().isCreated());
    var switched =
        mvc.perform(
                post("/api/v1/auth/organization")
                    .with(csrf())
                    .header("Authorization", "Bearer " + viewerAccess)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"organizationId\":\"" + organizationId + "\"}"))
            .andExpect(status().isOk())
            .andReturn();
    viewerAccess =
        mapper.readTree(switched.getResponse().getContentAsString()).path("accessToken").asText();
    mvc.perform(
            post("/api/v1/projects")
                .with(csrf())
                .header("Authorization", "Bearer " + viewerAccess)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Forbidden\",\"slug\":\"forbidden\"}"))
        .andExpect(status().isForbidden());

    mvc.perform(
            get("/api/v1/projects/{id}", projectId)
                .header("Authorization", "Bearer " + viewerAccess))
        .andExpect(status().isOk());

    var outsider = register("outsider@example.test", "Other Org");
    String outsiderAccess =
        mapper.readTree(outsider.getResponse().getContentAsString()).path("accessToken").asText();
    mvc.perform(
            get("/api/v1/projects/{id}", projectId)
                .header("Authorization", "Bearer " + outsiderAccess))
        .andExpect(status().isNotFound());
  }

  @Test
  void loginAttemptsAreRateLimitedByAccount() throws Exception {
    for (int attempt = 0; attempt < 5; attempt++) {
      mvc.perform(
              post("/api/v1/auth/login")
                  .with(csrf())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"email\":\"limited@example.test\",\"password\":\"incorrect\"}"))
          .andExpect(status().isUnauthorized());
    }
    mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"limited@example.test\",\"password\":\"incorrect\"}"))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
  }

  private org.springframework.test.web.servlet.MvcResult register(String email, String organization)
      throws Exception {
    registerPending(email, organization);
    TestAuthMail.verify(mvc, jdbc, mail, email);
    return mvc.perform(
            post("/api/v1/auth/login")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        java.util.Map.of("email", email, "password", "a-strong-password-123"))))
        .andExpect(status().isOk())
        .andReturn();
  }

  private void registerPending(String email, String organization) throws Exception {
    mvc.perform(
            post("/api/v1/auth/register")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    mapper.writeValueAsString(
                        new Registration(
                            email, "a-strong-password-123", "Test User", organization))))
        .andExpect(status().isAccepted());
  }

  private record Registration(
      String email, String password, String displayName, String organizationName) {}
}

package dev.errorintelligence.identity;

import static dev.errorintelligence.identity.IdentityDtos.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@org.springframework.validation.annotation.Validated
@RequestMapping("/api/v1")
public class IdentityController {
  private final IdentityService service;
  private final AuthRateLimiter limiter;
  private final AccountRecoveryService recovery;
  private final boolean secure;

  public IdentityController(
      IdentityService service,
      AuthRateLimiter limiter,
      AccountRecoveryService recovery,
      @Value("${app.auth.secure-cookie:true}") boolean secure) {
    this.service = service;
    this.limiter = limiter;
    this.recovery = recovery;
    this.secure = secure;
  }

  @GetMapping("/auth/csrf")
  public Map<String, String> csrf(CsrfToken token, HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  @PostMapping("/auth/register")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void register(
      @Valid @RequestBody Registration input,
      HttpServletRequest request,
      HttpServletResponse response) {
    limiter.check("register-ip", request.getRemoteAddr(), 10);
    limiter.check("register-account", IdentityService.normalizeEmail(input.email()), 3);
    service.register(input);
    response.setHeader("Cache-Control", "no-store");
  }

  @PostMapping("/auth/verification/resend")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void resendVerification(
      @Valid @RequestBody EmailInput input,
      HttpServletRequest request,
      HttpServletResponse response) {
    limiter.check("verification-ip", request.getRemoteAddr(), 15);
    limiter.check("verification-account", IdentityService.normalizeEmail(input.email()), 3);
    recovery.resendVerification(input.email());
    response.setHeader("Cache-Control", "no-store");
  }

  @PostMapping("/auth/verification/confirm")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void confirmVerification(
      @Valid @RequestBody TokenInput input,
      HttpServletRequest request,
      HttpServletResponse response) {
    limiter.check("verification-confirm-ip", request.getRemoteAddr(), 30);
    recovery.verifyEmail(input.token());
    response.setHeader("Cache-Control", "no-store");
  }

  @PostMapping("/auth/password/forgot")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public void forgotPassword(
      @Valid @RequestBody EmailInput input,
      HttpServletRequest request,
      HttpServletResponse response) {
    limiter.check("password-forgot-ip", request.getRemoteAddr(), 15);
    limiter.check("password-forgot-account", IdentityService.normalizeEmail(input.email()), 3);
    recovery.requestPasswordReset(input.email());
    response.setHeader("Cache-Control", "no-store");
  }

  @PostMapping("/auth/password/reset")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void resetPassword(
      @Valid @RequestBody PasswordResetInput input,
      HttpServletRequest request,
      HttpServletResponse response) {
    limiter.check("password-reset-ip", request.getRemoteAddr(), 30);
    recovery.resetPassword(input.token(), input.newPassword());
    setCookie(response, "", 0);
    rotateCsrf(response);
  }

  @PostMapping("/auth/login")
  public SessionResponse login(
      @Valid @RequestBody Login input, HttpServletRequest request, HttpServletResponse response) {
    limiter.check("login-ip", request.getRemoteAddr(), 30);
    limiter.check("login-account", IdentityService.normalizeEmail(input.email()), 5);
    IssuedSession issued = service.login(input);
    rotateCsrf(response);
    return cookie(issued, response);
  }

  @PostMapping("/auth/refresh")
  public SessionResponse refresh(
      @CookieValue(name = "EI_REFRESH", required = false) String token,
      HttpServletRequest request,
      HttpServletResponse response) {
    limiter.check("refresh-ip", request.getRemoteAddr(), 60);
    return cookie(service.refresh(token), response);
  }

  @PostMapping("/auth/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(
      @CookieValue(name = "EI_REFRESH", required = false) String token,
      HttpServletResponse response) {
    service.logout(token);
    setCookie(response, "", 0);
    rotateCsrf(response);
  }

  @GetMapping("/auth/me")
  public Account me(@AuthenticationPrincipal Jwt jwt) {
    return service.me(service.tenant(jwt));
  }

  @PostMapping("/auth/organization")
  public SessionResponse switchOrganization(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody SwitchOrganization input,
      HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    return service.switchOrganization(service.tenant(jwt), input.organizationId());
  }

  @PostMapping("/organizations")
  @ResponseStatus(HttpStatus.CREATED)
  public Organization createOrganization(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody OrganizationInput input) {
    return service.addOrganization(service.tenant(jwt), input.name());
  }

  @GetMapping("/organizations/{id}/members")
  public List<Member> members(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0")
          @jakarta.validation.constraints.Min(0)
          @jakarta.validation.constraints.Max(1000000)
          int offset) {
    return service.members(service.tenant(jwt), id, offset);
  }

  @PostMapping("/organizations/{id}/members")
  @ResponseStatus(HttpStatus.CREATED)
  public void addMember(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody MemberInput input) {
    service.addMember(service.tenant(jwt), id, input);
  }

  @PatchMapping("/organizations/{id}/members/{userId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void role(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @PathVariable UUID userId,
      @Valid @RequestBody RoleInput input) {
    service.changeMember(service.tenant(jwt), id, userId, input.role());
  }

  @DeleteMapping("/organizations/{id}/members/{userId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void remove(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID userId) {
    service.changeMember(service.tenant(jwt), id, userId, null);
  }

  private SessionResponse cookie(IssuedSession issued, HttpServletResponse response) {
    setCookie(response, issued.refreshToken(), 30 * 24 * 60 * 60);
    return issued.response();
  }

  private void setCookie(HttpServletResponse response, String token, long age) {
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from("EI_REFRESH", token)
            .httpOnly(true)
            .secure(secure)
            .sameSite("Strict")
            .path("/api/v1/auth")
            .maxAge(age)
            .build()
            .toString());
    response.setHeader("Cache-Control", "no-store");
  }

  private void rotateCsrf(HttpServletResponse response) {
    response.addHeader(
        HttpHeaders.SET_COOKIE,
        ResponseCookie.from("XSRF-TOKEN", "")
            .path("/")
            .httpOnly(true)
            .secure(secure)
            .sameSite("Strict")
            .maxAge(0)
            .build()
            .toString());
  }
}

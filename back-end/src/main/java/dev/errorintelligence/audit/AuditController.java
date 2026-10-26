package dev.errorintelligence.audit;

import static dev.errorintelligence.audit.AuditDtos.*;

import dev.errorintelligence.identity.IdentityService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class AuditController {
  private final AuditService audit;
  private final IdentityService identity;

  public AuditController(AuditService audit, IdentityService identity) {
    this.audit = audit;
    this.identity = identity;
  }

  @GetMapping("/api/v1/audit")
  public Page recent(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
    return audit.recent(identity.tenant(jwt), limit);
  }
}

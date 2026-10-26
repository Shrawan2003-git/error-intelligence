package dev.errorintelligence.ai;

import static dev.errorintelligence.ai.DiagnosticDtos.*;

import dev.errorintelligence.identity.AuthRateLimiter;
import dev.errorintelligence.identity.IdentityDtos.Tenant;
import dev.errorintelligence.identity.IdentityService;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AiAnalysisController {
  private final AiAnalysisService analyses;
  private final IdentityService identity;
  private final AuthRateLimiter limiter;

  public AiAnalysisController(
      AiAnalysisService analyses, IdentityService identity, AuthRateLimiter limiter) {
    this.analyses = analyses;
    this.identity = identity;
    this.limiter = limiter;
  }

  @PostMapping("/api/v1/incidents/{incidentId}/ai/analyze")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Accepted analyze(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID incidentId) {
    Tenant tenant = identity.tenant(jwt);
    tenant.requireContributor();
    limiter.check("ai-analysis", tenant.organizationId().toString(), 5);
    return analyses.enqueue(tenant, incidentId);
  }

  @GetMapping("/api/v1/incidents/{incidentId}/ai/analyses/{analysisId}")
  public Analysis get(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID incidentId,
      @PathVariable UUID analysisId) {
    return analyses.get(identity.tenant(jwt), incidentId, analysisId);
  }
}

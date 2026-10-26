package dev.errorintelligence.similarity;

import dev.errorintelligence.identity.AuthRateLimiter;
import dev.errorintelligence.identity.IdentityService;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SimilarityController {
  private final SimilarityService similarity;
  private final IdentityService identity;
  private final AuthRateLimiter limiter;

  public SimilarityController(
      SimilarityService similarity, IdentityService identity, AuthRateLimiter limiter) {
    this.similarity = similarity;
    this.identity = identity;
    this.limiter = limiter;
  }

  @PostMapping("/api/v1/incidents/{incidentId}/similar")
  public SimilarityDtos.SearchResult find(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID incidentId) {
    var tenant = identity.tenant(jwt);
    tenant.requireContributor();
    limiter.check("similarity-search", tenant.organizationId().toString(), 5);
    return similarity.search(tenant, incidentId);
  }
}

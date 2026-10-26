package dev.errorintelligence.analytics;

import static dev.errorintelligence.analytics.AnalyticsDtos.*;

import dev.errorintelligence.identity.IdentityService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
public class AnalyticsController {
  private final AnalyticsService analytics;
  private final IdentityService identity;

  public AnalyticsController(AnalyticsService analytics, IdentityService identity) {
    this.analytics = analytics;
    this.identity = identity;
  }

  @GetMapping("/api/v1/projects/{projectId}/analytics/overview")
  public Overview overview(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID projectId,
      @RequestParam(defaultValue = "30") @Min(7) @Max(90) int days) {
    return analytics.overview(identity.tenant(jwt), projectId, days);
  }
}

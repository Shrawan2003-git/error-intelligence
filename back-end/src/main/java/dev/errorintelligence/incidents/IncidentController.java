package dev.errorintelligence.incidents;

import static dev.errorintelligence.incidents.IncidentDtos.*;

import dev.errorintelligence.identity.IdentityService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
public class IncidentController {
  private final IncidentService service;
  private final IdentityService identity;

  public IncidentController(IncidentService service, IdentityService identity) {
    this.service = service;
    this.identity = identity;
  }

  @GetMapping("/api/v1/projects/{projectId}/incidents")
  public Page<Summary> list(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID projectId,
      @RequestParam(required = false) Status status,
      @RequestParam(required = false) @Size(max = 80) String environment,
      @RequestParam(required = false) @Size(max = 120) String release,
      @RequestParam(required = false) @Size(max = 120) String q,
      @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return service.list(
        identity.tenant(jwt), projectId, status, environment, release, q, page, size);
  }

  @GetMapping("/api/v1/incidents/{id}")
  public Detail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.detail(identity.tenant(jwt), id);
  }

  @PatchMapping("/api/v1/incidents/{id}")
  public Summary update(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Update input) {
    return service.update(identity.tenant(jwt), id, input);
  }

  @PostMapping("/api/v1/incidents/{id}/comments")
  @ResponseStatus(HttpStatus.CREATED)
  public Mutation comment(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody CommentInput input) {
    return service.comment(identity.tenant(jwt), id, input);
  }

  @PostMapping("/api/v1/incidents/{id}/resolve")
  @ResponseStatus(HttpStatus.CREATED)
  public Mutation resolve(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody ResolveInput input) {
    return service.resolve(identity.tenant(jwt), id, input);
  }
}

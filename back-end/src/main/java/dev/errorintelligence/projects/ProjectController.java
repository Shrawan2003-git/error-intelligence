package dev.errorintelligence.projects;

import static dev.errorintelligence.projects.ProjectDtos.*;

import dev.errorintelligence.identity.AuthRateLimiter;
import dev.errorintelligence.identity.IdentityService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/projects")
@Validated
public class ProjectController {
  private final ProjectService service;
  private final IdentityService identity;
  private final AuthRateLimiter limiter;

  public ProjectController(
      ProjectService service, IdentityService identity, AuthRateLimiter limiter) {
    this.service = service;
    this.identity = identity;
    this.limiter = limiter;
  }

  @GetMapping
  public PageResult<Detail> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return service.list(identity.tenant(jwt), page, size);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public Detail create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody Create input) {
    return service.create(identity.tenant(jwt), input);
  }

  @GetMapping("/{id}")
  public Detail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return Detail.from(service.require(identity.tenant(jwt), id));
  }

  @PatchMapping("/{id}")
  public Detail rename(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @Valid @RequestBody Rename input) {
    return service.rename(identity.tenant(jwt), id, input);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void archive(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    service.archive(identity.tenant(jwt), id);
  }

  @GetMapping("/{id}/environments")
  public List<Named> environments(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.environments(identity.tenant(jwt), id);
  }

  @PostMapping("/{id}/environments")
  @ResponseStatus(HttpStatus.CREATED)
  public Named environment(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody NamedInput input) {
    return service.addEnvironment(identity.tenant(jwt), id, input.name());
  }

  @GetMapping("/{id}/releases")
  public List<Named> releases(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return service.releases(identity.tenant(jwt), id);
  }

  @PostMapping("/{id}/releases")
  @ResponseStatus(HttpStatus.CREATED)
  public Named release(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody ReleaseInput input) {
    return service.addRelease(identity.tenant(jwt), id, input.version());
  }

  @GetMapping("/{id}/keys")
  public List<Key> keys(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int offset) {
    return service.keys(identity.tenant(jwt), id, offset);
  }

  @PostMapping("/{id}/keys")
  @ResponseStatus(HttpStatus.CREATED)
  public CreatedKey key(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody KeyInput input,
      HttpServletResponse response) {
    response.setHeader("Cache-Control", "no-store");
    limiter.check("key-create-user", identity.tenant(jwt).userId().toString(), 10);
    return service.createKey(identity.tenant(jwt), id, input.label());
  }

  @DeleteMapping("/{id}/keys/{keyId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id, @PathVariable UUID keyId) {
    service.revoke(identity.tenant(jwt), id, keyId);
  }
}

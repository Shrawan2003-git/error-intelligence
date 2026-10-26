package dev.errorintelligence.common.system;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemController {
  private final String version;
  private final boolean cacheRequired;
  private final String phase;

  public SystemController(
      @Value("${app.version}") String version,
      @Value("${app.cache-required:true}") boolean cacheRequired,
      @Value("${app.phase:AI_DIAGNOSTICS}") String phase) {
    this.version = version;
    this.cacheRequired = cacheRequired;
    this.phase = phase;
  }

  @GetMapping("/api/v1/system")
  public SystemResponse system() {
    return new SystemResponse("Error Intelligence", version, phase, cacheRequired);
  }

  public record SystemResponse(String name, String version, String phase, boolean cacheRequired) {}
}

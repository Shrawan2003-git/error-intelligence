package dev.errorintelligence.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/** Restricts the unauthenticated Prometheus scrape to an explicitly configured network. */
public class PrometheusScrapeFilter extends OncePerRequestFilter {
  private static final String PATH = "/actuator/prometheus";
  private final List<IpAddressMatcher> allowedAddresses;

  public PrometheusScrapeFilter(String cidrs) {
    this.allowedAddresses =
        java.util.Arrays.stream(cidrs.split(","))
            .map(String::strip)
            .filter(value -> !value.isEmpty())
            .map(IpAddressMatcher::new)
            .toList();
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().equals(request.getContextPath() + PATH);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    if (allowedAddresses.stream().anyMatch(matcher -> matcher.matches(request))) {
      chain.doFilter(request, response);
    } else {
      response.sendError(HttpServletResponse.SC_NOT_FOUND);
    }
  }
}

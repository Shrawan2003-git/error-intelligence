package dev.errorintelligence.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.errorintelligence.common.api.ApiProblem;
import dev.errorintelligence.common.observability.PrometheusScrapeFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
public class SecurityConfiguration {
  @Bean
  SecurityFilterChain securityFilterChain(
      HttpSecurity http, ObjectMapper mapper, PrometheusScrapeFilter prometheusScrapeFilter)
      throws Exception {
    CookieCsrfTokenRepository csrfRepository = new CookieCsrfTokenRepository();
    csrfRepository.setCookieCustomizer(cookie -> cookie.sameSite("Strict"));
    var csrfAuthentication =
        new org.springframework.security.web.csrf.CsrfAuthenticationStrategy(csrfRepository);
    return http.authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        HttpMethod.GET,
                        "/api/v1/system",
                        "/api/v1/auth/csrf",
                        "/actuator/health",
                        "/actuator/health/liveness",
                        "/actuator/health/readiness",
                        "/actuator/prometheus")
                    .permitAll()
                    .requestMatchers(
                        HttpMethod.POST,
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/verification/resend",
                        "/api/v1/auth/verification/confirm",
                        "/api/v1/auth/password/forgot",
                        "/api/v1/auth/password/reset",
                        "/api/v1/auth/refresh",
                        "/api/v1/auth/logout",
                        "/ingest/v1/events")
                    .permitAll()
                    .requestMatchers(
                        "/api/v1/auth/me",
                        "/api/v1/auth/organization",
                        "/api/v1/organizations/**",
                        "/api/v1/audit",
                        "/api/v1/projects/**",
                        "/api/v1/incidents/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .addFilterBefore(prometheusScrapeFilter, AuthorizationFilter.class)
        .csrf(
            csrf ->
                csrf.ignoringRequestMatchers("/ingest/v1/events")
                    .csrfTokenRepository(csrfRepository)
                    .sessionAuthenticationStrategy(
                        (authentication, request, response) -> {
                          // Validating a stateless bearer on each request is not a new browser
                          // login.
                          // Rotating the shared CSRF cookie here races with other tabs' refreshes.
                          if (!(authentication
                              instanceof
                              org.springframework.security.oauth2.server.resource.authentication
                                  .JwtAuthenticationToken))
                            csrfAuthentication.onAuthentication(authentication, request, response);
                        })
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .oauth2ResourceServer(
            oauth ->
                oauth
                    .jwt(jwt -> {})
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            writeProblem(
                                mapper,
                                response,
                                HttpStatus.UNAUTHORIZED,
                                "AUTHENTICATION_REQUIRED",
                                "Authentication is required.")))
        .sessionManagement(
            session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .formLogin(AbstractHttpConfigurer::disable)
        .httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable)
        .requestCache(AbstractHttpConfigurer::disable)
        .headers(
            headers ->
                headers.contentSecurityPolicy(
                    csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'")))
        .exceptionHandling(
            errors ->
                errors
                    .authenticationEntryPoint(
                        (request, response, exception) ->
                            writeProblem(
                                mapper,
                                response,
                                HttpStatus.UNAUTHORIZED,
                                "AUTHENTICATION_REQUIRED",
                                "Authentication is required."))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            writeProblem(
                                mapper,
                                response,
                                HttpStatus.FORBIDDEN,
                                "ACCESS_DENIED",
                                "You do not have permission to perform this action.")))
        .build();
  }

  private void writeProblem(
      ObjectMapper mapper,
      HttpServletResponse response,
      HttpStatus status,
      String code,
      String detail)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    mapper.writeValue(response.getOutputStream(), ApiProblem.create(status, code, detail));
  }
}

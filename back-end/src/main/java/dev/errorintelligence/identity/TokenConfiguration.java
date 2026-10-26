package dev.errorintelligence.identity;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

@Configuration(proxyBeanMethods = false)
public class TokenConfiguration {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  SecretKeySpec signingKey(@Value("${app.auth.signing-key}") String value) {
    byte[] bytes = Base64.getDecoder().decode(value);
    if (bytes.length < 32)
      throw new IllegalStateException(
          "AUTH_SIGNING_KEY must contain at least 256 random bits in Base64.");
    return new SecretKeySpec(bytes, "HmacSHA256");
  }

  @Bean
  JwtEncoder jwtEncoder(SecretKeySpec key) {
    return new NimbusJwtEncoder(new ImmutableSecret<>(key));
  }

  @Bean
  JwtDecoder jwtDecoder(
      SecretKeySpec key, JdbcTemplate jdbc, @Value("${app.auth.issuer}") String issuer) {
    NimbusJwtDecoder decoder =
        NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    OAuth2TokenValidator<Jwt> liveSession =
        jwt -> {
          try {
            if (!jwt.getAudience().contains("error-intelligence-api")) return invalid();
            Integer count =
                jdbc.queryForObject(
                    "select count(*) from intelligence.sessions s join intelligence.memberships m on m.organization_id=s.organization_id and m.user_id=s.user_id where s.id=? and s.user_id=? and s.organization_id=? and s.revoked_at is null and s.expires_at>?",
                    Integer.class,
                    UUID.fromString(jwt.getClaimAsString("sid")),
                    UUID.fromString(jwt.getSubject()),
                    UUID.fromString(jwt.getClaimAsString("org")),
                    java.sql.Timestamp.from(Instant.now()));
            return count != null && count == 1 ? OAuth2TokenValidatorResult.success() : invalid();
          } catch (RuntimeException exception) {
            return invalid();
          }
        };
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), liveSession));
    return decoder;
  }

  private static OAuth2TokenValidatorResult invalid() {
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error("invalid_token", "The session is invalid.", null));
  }
}

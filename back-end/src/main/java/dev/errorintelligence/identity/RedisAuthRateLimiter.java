package dev.errorintelligence.identity;

import dev.errorintelligence.common.api.DomainException;
import dev.errorintelligence.common.security.Credentials;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@Profile("!native")
public class RedisAuthRateLimiter implements AuthRateLimiter {
  private final StringRedisTemplate redis;
  private static final DefaultRedisScript<Long> COUNT =
      new DefaultRedisScript<>(
          "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],60) end; return n",
          Long.class);

  public RedisAuthRateLimiter(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public void check(String bucket, String identifier, int limit) {
    Long count;
    try {
      count =
          redis.execute(COUNT, List.of("ei:auth:" + bucket + ":" + Credentials.digest(identifier)));
    } catch (RuntimeException exception) {
      throw unavailable();
    }
    if (count == null) throw unavailable();
    if (count > limit)
      throw new DomainException(
          HttpStatus.TOO_MANY_REQUESTS,
          "RATE_LIMITED",
          "Too many attempts. Try again in one minute.");
  }

  private DomainException unavailable() {
    return new DomainException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "AUTH_UNAVAILABLE",
        "Sign in is temporarily unavailable. Try again shortly.");
  }
}

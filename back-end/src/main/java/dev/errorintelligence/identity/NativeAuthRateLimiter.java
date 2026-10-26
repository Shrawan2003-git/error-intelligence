package dev.errorintelligence.identity;

import dev.errorintelligence.common.api.DomainException;
import dev.errorintelligence.common.security.Credentials;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Single-process rate limits for loopback-only development; production uses Redis. */
@Component
@Profile("native")
public class NativeAuthRateLimiter implements AuthRateLimiter {
  private static final int MAX_BUCKETS = 10_000;
  private static final long WINDOW_MILLIS = 60_000;
  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  @Override
  public synchronized void check(String bucket, String identifier, int limit) {
    long now = System.currentTimeMillis();
    if (windows.size() >= MAX_BUCKETS) {
      windows.entrySet().removeIf(entry -> entry.getValue().expiresAt() <= now);
      if (windows.size() >= MAX_BUCKETS)
        throw new DomainException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "AUTH_UNAVAILABLE",
            "Sign in is temporarily unavailable. Try again shortly.");
    }
    String key = bucket + ":" + Credentials.digest(identifier);
    Window window =
        windows.compute(
            key,
            (ignored, previous) -> {
              if (previous == null || previous.expiresAt() <= now)
                return new Window(now + WINDOW_MILLIS);
              previous.count().incrementAndGet();
              return previous;
            });
    if (window.count().get() > limit)
      throw new DomainException(
          HttpStatus.TOO_MANY_REQUESTS,
          "RATE_LIMITED",
          "Too many attempts. Try again in one minute.");
  }

  private record Window(long expiresAt, AtomicInteger count) {
    Window(long expiresAt) {
      this(expiresAt, new AtomicInteger(1));
    }
  }
}

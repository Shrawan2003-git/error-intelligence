package dev.errorintelligence.identity;

public interface AuthRateLimiter {
  void check(String bucket, String identifier, int limit);
}

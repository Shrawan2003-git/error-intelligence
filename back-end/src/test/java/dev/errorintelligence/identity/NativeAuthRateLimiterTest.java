package dev.errorintelligence.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.errorintelligence.common.api.DomainException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class NativeAuthRateLimiterTest {
  @Test
  void deniesRequestsOverTheConfiguredFixedWindowLimit() {
    var limiter = new NativeAuthRateLimiter();
    limiter.check("login-account", "owner@example.test", 2);
    limiter.check("login-account", "owner@example.test", 2);

    assertThatThrownBy(() -> limiter.check("login-account", "owner@example.test", 2))
        .isInstanceOf(DomainException.class)
        .satisfies(
            exception ->
                assertThat(((DomainException) exception).status())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS))
        .hasMessage("Too many attempts. Try again in one minute.");
  }
}

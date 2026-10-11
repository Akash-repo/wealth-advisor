package com.wealth.advisor.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.wealth.advisor.config.TestProperties;
import org.junit.jupiter.api.Test;

/**
 * Plain JUnit, no Spring context — {@link ChatRateLimiter} has no injected dependencies, so a
 * direct {@code new} is a faithful test, same "constructor injection means no container needed to
 * unit test" point made in {@code mental_model_spring_with_adk.md} §1.3. Runs in milliseconds,
 * unlike {@code PortfolioEvalTest}'s real API calls — exactly the fast, free regression coverage
 * called out as missing in {@code Optimization.md} P4.
 */
class ChatRateLimiterTest {

  private static final int LIMIT = TestProperties.defaults().chat().maxRequestsPerMinute();

  @Test
  void tryAcquire_allowsUpToTheLimitThenRejects() {
    ChatRateLimiter limiter = new ChatRateLimiter(TestProperties.defaults());
    String userId = "alice";

    for (int i = 0; i < LIMIT; i++) {
      assertThat(limiter.tryAcquire(userId)).as("request #%d should be allowed", i + 1).isTrue();
    }
    assertThat(limiter.tryAcquire(userId))
        .as("request #%d, one past the limit, should be rejected", LIMIT + 1)
        .isFalse();
  }

  @Test
  void tryAcquire_tracksEachUserIndependently() {
    ChatRateLimiter limiter = new ChatRateLimiter(TestProperties.defaults());

    for (int i = 0; i < LIMIT; i++) {
      assertThat(limiter.tryAcquire("alice")).isTrue();
    }
    assertThat(limiter.tryAcquire("alice")).as("alice is now over her own limit").isFalse();
    assertThat(limiter.tryAcquire("bob")).as("bob has his own, unaffected quota").isTrue();
  }
}

package com.wealth.advisor.web;

import java.time.Duration;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.stereotype.Component;

/**
 * Caps how many {@code /api/chat} turns one authenticated user can send per rolling window —
 * closes the gap where self-service signup plus a public Cloud Run URL meant any account could
 * send unlimited messages, each costing at least 2 real Gemini calls (see {@code Optimization.md}
 * P1-3).
 *
 * <p><strong>Sliding-window log, in memory, per user</strong> — for each {@code userId}, keeps a
 * deque of this window's request timestamps; {@link #tryAcquire} prunes anything older than the
 * window before checking/adding. Deliberately not a fixed-window counter (which lets 2×the limit
 * through across a window boundary) and deliberately not a shared external store (Redis/Firestore
 * counters) — an in-memory-per-instance limiter is the right amount of machinery for a
 * scale-to-zero-by-default Cloud Run service with a small trusted user base today. The real
 * limitation to know about: this does <em>not</em> coordinate across multiple Cloud Run instances,
 * so a user whose requests land on 3 different instances effectively gets 3× the limit. Revisit
 * with a Firestore- or Redis-backed counter only if this app is ever scaled past a couple of
 * concurrent instances — not worth the complexity before then.
 *
 * <p>{@code MAX_REQUESTS_PER_WINDOW} is generous relative to how fast a real user can even
 * generate turns: a chat turn already takes ~7-10s (two Gemini calls, per {@code
 * ARCHITECTURE.md}), so a human waiting for replies tops out well under this limit — it's aimed at
 * a scripted/careless burst, not a real conversation.
 */
@Component
public class ChatRateLimiter {

  // Package-private (not private) so ChatRateLimiterTest can assert against the real limit
  // instead of duplicating it as a magic number that could silently drift out of sync.
  static final int MAX_REQUESTS_PER_WINDOW = 20;
  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final Map<String, Deque<Long>> requestTimestampsByUser = new ConcurrentHashMap<>();

  /**
   * Records one request attempt for {@code userId} and returns whether it's allowed. Thread-safe
   * per user (synchronizes on that user's own deque, so different users never block each other).
   */
  public boolean tryAcquire(String userId) {
    long now = System.currentTimeMillis();
    long windowStart = now - WINDOW.toMillis();
    Deque<Long> timestamps =
        requestTimestampsByUser.computeIfAbsent(userId, key -> new ConcurrentLinkedDeque<>());
    synchronized (timestamps) {
      while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
        timestamps.pollFirst();
      }
      if (timestamps.size() >= MAX_REQUESTS_PER_WINDOW) {
        return false;
      }
      timestamps.addLast(now);
      return true;
    }
  }
}

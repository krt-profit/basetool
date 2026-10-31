/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.frontend.kernel.livesync;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.web.socket.WebSocketSession;

/**
 * The live-sync token buckets (REQ-FE-015): per session for {@code changed}, presence and {@code
 * subscribe} frames, and per topic for accepted {@code changed} frames.
 */
@RequiredArgsConstructor
final class LiveSyncRateLimiter {

  /**
   * Token-bucket capacity for inbound {@code changed} frames per session — the burst a session may
   * relay before throttling kicks in. Sits far above any human edit cadence, so a legitimate
   * rapid-editing viewer never trips it; it only bounds a crafted client emitting {@code changed}
   * frames in a loop.
   */
  static final int CHANGED_BURST = 40;

  /** Token-bucket refill rate for inbound {@code changed} frames, in tokens per second. */
  private static final double CHANGED_REFILL_PER_SEC = 20.0;

  /**
   * Per-session token-bucket capacity for inbound presence frames ({@code focus} / {@code
   * heartbeat} / {@code blur}).
   */
  static final int PRESENCE_BURST = 20;

  /** Token-bucket refill rate for inbound presence control frames, in tokens per second. */
  private static final double PRESENCE_REFILL_PER_SEC = 10.0;

  /**
   * Per-session token-bucket capacity for inbound {@code subscribe} frames, bounding the rate of
   * authorization probes; 1.5 times {@link LiveSyncSubscriptions#MAX_TOPICS_PER_SESSION}.
   */
  static final int SUBSCRIBE_BURST = 24;

  /** Refill rate for inbound {@code subscribe} frames, in tokens per second. */
  private static final double SUBSCRIBE_REFILL_PER_SEC = 1.0;

  /**
   * Per-topic token-bucket capacity for accepted {@code changed} frames, bounding a room's
   * aggregate relay and fan-out rate across all publishers.
   */
  static final int TOPIC_CHANGED_BURST = 200;

  /** Per-topic refill rate for accepted {@code changed} frames, in tokens per second. */
  private static final double TOPIC_CHANGED_REFILL_PER_SEC = 100.0;

  /** Idle age after which the reaper drops a per-topic {@code changed} bucket. */
  static final long TOPIC_BUCKET_IDLE_REAP_NANOS = TimeUnit.SECONDS.toNanos(60);

  private static final String ATTR_CHANGED_RATE = "livesync.changedRate";
  private static final String ATTR_PRESENCE_RATE = "livesync.presenceRate";
  private static final String ATTR_SUBSCRIBE_RATE = "livesync.subscribeRate";

  /** Monotonic nanosecond clock for refills and the idle-bucket reaper. */
  private final LongSupplier nanoClock;

  /** Per-topic {@code changed}-frame token buckets keyed by canonical topic. */
  private final Map<String, TopicRateState> changedRateByTopic = new ConcurrentHashMap<>();

  /**
   * Per-session rate limit on inbound {@code changed} frames ({@link #CHANGED_BURST} refilled at
   * {@link #CHANGED_REFILL_PER_SEC}/s).
   *
   * @param session the session that sent the frame
   * @return {@code true} to relay the frame, {@code false} to drop it as throttled
   */
  boolean allowChangedFrame(@NotNull WebSocketSession session) {
    return allowFrame(session, ATTR_CHANGED_RATE, CHANGED_BURST, CHANGED_REFILL_PER_SEC);
  }

  /**
   * Per-session rate limit on inbound presence frames ({@code focus} / {@code heartbeat} / {@code
   * blur}).
   *
   * @param session the session that sent the frame
   * @return {@code true} to process the frame, {@code false} to drop it as throttled
   */
  boolean allowPresenceFrame(@NotNull WebSocketSession session) {
    return allowFrame(session, ATTR_PRESENCE_RATE, PRESENCE_BURST, PRESENCE_REFILL_PER_SEC);
  }

  /**
   * Per-session rate limit on inbound {@code subscribe} frames, bounding the authorization probes.
   *
   * @param session the session that sent the frame
   * @return {@code true} to process the subscribe, {@code false} to drop it as throttled
   */
  boolean allowSubscribeFrame(@NotNull WebSocketSession session) {
    return allowFrame(session, ATTR_SUBSCRIBE_RATE, SUBSCRIBE_BURST, SUBSCRIBE_REFILL_PER_SEC);
  }

  /**
   * Shared per-session token bucket, stored unsynchronised in the session attributes since one
   * session's frames arrive serially.
   *
   * @param session the session that sent the frame
   * @param attrKey the session-attribute key holding this bucket's state
   * @param burst the bucket capacity
   * @param refillPerSec the token refill rate per second
   * @return {@code true} to process the frame, {@code false} to drop it as throttled
   */
  private boolean allowFrame(
      @NotNull WebSocketSession session, @NotNull String attrKey, int burst, double refillPerSec) {
    long now = nanoClock.getAsLong();
    SessionRateState state;
    if (session.getAttributes().get(attrKey) instanceof SessionRateState existing) {
      state = existing;
    } else {
      state = new SessionRateState(burst, now);
      session.getAttributes().put(attrKey, state);
    }
    double elapsedSeconds = (now - state.lastRefillNanos) / 1_000_000_000.0;
    state.tokens = Math.min(burst, state.tokens + elapsedSeconds * refillPerSec);
    state.lastRefillNanos = now;
    if (state.tokens >= 1.0) {
      state.tokens -= 1.0;
      return true;
    }
    return false;
  }

  /**
   * Per-topic rate limit on accepted {@code changed} frames; the shared bucket is updated under its
   * own monitor.
   *
   * @param topic the topic the frame targets
   * @return {@code true} to relay the frame, {@code false} to drop it as throttled
   */
  boolean allowTopicChanged(@NotNull LiveSyncTopic topic) {
    long now = nanoClock.getAsLong();
    TopicRateState state =
        changedRateByTopic.computeIfAbsent(
            topic.canonical(), _ -> new TopicRateState(TOPIC_CHANGED_BURST, now));
    synchronized (state) {
      double elapsedSeconds = (now - state.lastRefillNanos) / 1_000_000_000.0;
      state.tokens =
          Math.min(
              TOPIC_CHANGED_BURST, state.tokens + elapsedSeconds * TOPIC_CHANGED_REFILL_PER_SEC);
      state.lastRefillNanos = now;
      if (state.tokens >= 1.0) {
        state.tokens -= 1.0;
        return true;
      }
      return false;
    }
  }

  /**
   * Drops per-topic {@code changed} buckets idle for at least {@link
   * #TOPIC_BUCKET_IDLE_REAP_NANOS}.
   *
   * @param nowNanos the current monotonic clock reading
   */
  void reapIdleTopicBuckets(long nowNanos) {
    long cutoff = nowNanos - TOPIC_BUCKET_IDLE_REAP_NANOS;
    changedRateByTopic
        .entrySet()
        .removeIf(entry -> {
          synchronized (entry.getValue()) {
            return entry.getValue().lastRefillNanos <= cutoff;
          }
        });
  }

  /**
   * The number of live per-topic {@code changed} buckets.
   *
   * @return the size of the per-topic bucket map
   */
  int topicBucketCount() {
    return changedRateByTopic.size();
  }

  /**
   * Mutable per-session token-bucket state: the current (fractional) token count and the clock
   * reading at the last refill. Stored in the WebSocket session attributes and touched only from
   * the single-threaded per-session message delivery, so it needs no synchronisation.
   */
  private static final class SessionRateState {
    private double tokens;
    private long lastRefillNanos;

    SessionRateState(double tokens, long lastRefillNanos) {
      this.tokens = tokens;
      this.lastRefillNanos = lastRefillNanos;
    }
  }

  /**
   * Per-topic token-bucket state for the {@code changed}-frame throttle, shared across publishers
   * and mutated under its own monitor.
   */
  private static final class TopicRateState {
    private double tokens;
    private long lastRefillNanos;

    TopicRateState(double tokens, long lastRefillNanos) {
      this.tokens = tokens;
      this.lastRefillNanos = lastRefillNanos;
    }
  }
}

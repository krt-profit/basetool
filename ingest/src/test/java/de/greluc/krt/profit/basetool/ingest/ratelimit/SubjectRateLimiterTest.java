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

package de.greluc.krt.profit.basetool.ingest.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import de.greluc.krt.profit.basetool.ingest.config.RateLimitProperties;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.support.LogCapture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the per-subject ingest rate limiter (REQ-INGEST-005, security audit
 * INGEST-RATELIMIT-1): the budget is enforced per JWT subject, independent across subjects, and
 * disabled cleanly when {@code app.rate-limit.enabled=false}.
 */
class SubjectRateLimiterTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

  private static RateLimitProperties props(int capacity, boolean enabled) {
    RateLimitProperties p = new RateLimitProperties();
    p.setEnabled(enabled);
    p.setCapacity(capacity);
    p.setRefillTokens(capacity);
    p.setRefillPeriod(Duration.ofMinutes(1));
    return p;
  }

  @Test
  void allowsUpToCapacityThenThrowsWithRetryAfter() {
    SubjectRateLimiter limiter = new SubjectRateLimiter(props(1, true), meterRegistry);

    limiter.requireWithinLimit("sub-a"); // consumes the only token

    RateLimitedException ex =
        assertThrows(RateLimitedException.class, () -> limiter.requireWithinLimit("sub-a"));
    assertThat(ex.getRetryAfterSeconds()).isPositive();
    // The rejection is counted once under the bounded `subject` bucket, never the JWT sub.
    assertThat(
            meterRegistry
                .get(MetricNames.RATELIMIT_REJECTIONS)
                .tag(MetricNames.TAG_BUCKET, MetricNames.BUCKET_SUBJECT)
                .counter()
                .count())
        .isEqualTo(1.0d);
    // (#1041 item 19) Both evaluations — the consumed one and the rejected one — are counted, so
    // requests (2) > rejections (1); this is the rejection-ratio denominator.
    assertThat(
            meterRegistry
                .get(MetricNames.RATELIMIT_REQUESTS)
                .tag(MetricNames.TAG_BUCKET, MetricNames.BUCKET_SUBJECT)
                .counter()
                .count())
        .isEqualTo(2.0d);
  }

  @Test
  void rejectionIsWarnedWithTheBudgetAndRetryDelayButNeverTheSubject() {
    // Until this line existed a per-subject 429 was invisible in the log — the operator saw only
    // "-> 429" and could not tell it apart from the coarse per-IP limiter. The subject itself
    // stays out of the message: it is already the `userId` MDC field (REQ-OBS-001/-004).
    SubjectRateLimiter limiter = new SubjectRateLimiter(props(1, true), meterRegistry);
    limiter.requireWithinLimit("2b9f1c3e-0000-4000-8000-abcdefabcdef");

    List<ILoggingEvent> events =
        LogCapture.capture(
            SubjectRateLimiter.class,
            Level.INFO,
            () -> {
              try {
                limiter.requireWithinLimit("2b9f1c3e-0000-4000-8000-abcdefabcdef");
              } catch (RateLimitedException expected) {
                // The throw is the contract; the log line is what is under test.
              }
            });

    assertThat(events).hasSize(1);
    assertThat(events.getFirst().getLevel()).isEqualTo(Level.WARN);
    assertThat(events.getFirst().getFormattedMessage())
        .contains("capacity=1")
        .contains("retryAfter=")
        .doesNotContain("2b9f1c3e");
  }

  @Test
  void anAcceptedCallLogsNothing() {
    // The access log already records every request; a line per accepted call would only add noise.
    SubjectRateLimiter limiter = new SubjectRateLimiter(props(5, true), meterRegistry);

    List<ILoggingEvent> events =
        LogCapture.capture(
            SubjectRateLimiter.class, Level.DEBUG, () -> limiter.requireWithinLimit("sub-a"));

    assertThat(events).isEmpty();
  }

  @Test
  void budgetsAreIndependentPerSubject() {
    SubjectRateLimiter limiter = new SubjectRateLimiter(props(1, true), meterRegistry);

    limiter.requireWithinLimit("sub-a"); // exhausts sub-a only

    // A different subject still has a full budget — the limit is per-sub, not global.
    assertThatCode(() -> limiter.requireWithinLimit("sub-b")).doesNotThrowAnyException();
  }

  @Test
  void disabledLimiterNeverThrows() {
    SubjectRateLimiter limiter = new SubjectRateLimiter(props(1, false), meterRegistry);

    assertThatCode(
            () -> {
              limiter.requireWithinLimit("sub-a");
              limiter.requireWithinLimit("sub-a");
            })
        .doesNotThrowAnyException();
    // A disabled limiter returns before evaluating a bucket, so nothing is counted (#1041 item 19).
    assertThat(meterRegistry.find(MetricNames.RATELIMIT_REQUESTS).counter()).isNull();
  }
}

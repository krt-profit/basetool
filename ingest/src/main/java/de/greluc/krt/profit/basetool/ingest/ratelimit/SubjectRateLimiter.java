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

import de.greluc.krt.profit.basetool.ingest.config.RateLimitProperties;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Per-subject token-bucket rate limiter for the ingest endpoints, keyed on the authenticated JWT
 * {@code sub} (REQ-INGEST-005).
 *
 * <p>Invoked after authentication, so the subject is always available. The bucket map is bounded by
 * {@link RateLimitBuckets#boundedLru(int)}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubjectRateLimiter {

  /** Hard cap on simultaneously-tracked subjects, bounding the bucket map's memory footprint. */
  static final int MAX_TRACKED_SUBJECTS = 50_000;

  private final RateLimitProperties properties;
  private final MeterRegistry meterRegistry;
  private final Map<String, Bucket> buckets = RateLimitBuckets.boundedLru(MAX_TRACKED_SUBJECTS);

  /**
   * Consumes one token from the subject's bucket; a no-op when rate limiting is disabled.
   *
   * @param sub the authenticated caller's JWT subject; never {@code null}.
   * @throws RateLimitedException when the subject has no token left, carrying the suggested {@code
   *     Retry-After} delay.
   */
  public void requireWithinLimit(@NotNull String sub) {
    if (!properties.enabled()) {
      return;
    }
    Bucket bucket =
        buckets.computeIfAbsent(
            sub,
            key ->
                RateLimitBuckets.newBucket(
                    properties.capacity(), properties.refillTokens(), properties.refillPeriod()));
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    meterRegistry
        .counter(MetricNames.RATELIMIT_REQUESTS, MetricNames.TAG_BUCKET, MetricNames.BUCKET_SUBJECT)
        .increment();
    if (!probe.isConsumed()) {
      meterRegistry
          .counter(
              MetricNames.RATELIMIT_REJECTIONS, MetricNames.TAG_BUCKET, MetricNames.BUCKET_SUBJECT)
          .increment();
      long retryAfterSeconds =
          Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
      log.warn(
          "Per-subject ingest rate limit exceeded (capacity={} per {}, retryAfter={}s)",
          properties.capacity(),
          properties.refillPeriod(),
          retryAfterSeconds);
      throw new RateLimitedException(retryAfterSeconds);
    }
  }
}

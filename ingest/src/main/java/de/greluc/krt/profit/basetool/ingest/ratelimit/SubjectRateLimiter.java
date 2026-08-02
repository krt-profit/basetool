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
import io.github.bucket4j.Bandwidth;
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
 * Per-authenticated-subject token-bucket rate limiter for the ingest endpoints (REQ-INGEST-005).
 *
 * <p>This is the gateway's <em>enforceable</em> throttle. The complementary per-IP {@link
 * de.greluc.krt.profit.basetool.ingest.filter.RateLimitingFilter} runs before the security filter
 * chain and keys on a client IP that a caller can rotate via a spoofed {@code X-Forwarded-For}
 * header, so it can only ever be a coarse front line. This limiter keys on the JWT {@code sub},
 * which is bound to a Keycloak identity and cannot be forged by the client, so it reliably bounds
 * how hard a single authenticated caller can drive the backend's import endpoints (security audit
 * INGEST-RATELIMIT-1). It is invoked from {@code IngestService} — i.e. after Spring Security has
 * authenticated the request — so the subject is always available.
 *
 * <p>The bucket map is bounded ({@link RateLimitBuckets#boundedLru(int)}) so a flood of distinct
 * subjects cannot grow it without limit.
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
   * Consumes one token from the calling subject's bucket, throwing when the budget is exhausted. A
   * no-op when rate limiting is disabled (e.g. the e2e stack sets {@code app.rate-limit.enabled=
   * false}).
   *
   * @param sub the authenticated caller's JWT subject; never {@code null}.
   * @throws RateLimitedException when the subject has no token left, carrying the suggested {@code
   *     Retry-After} delay.
   */
  public void requireWithinLimit(@NotNull String sub) {
    if (!properties.isEnabled()) {
      return;
    }
    Bucket bucket = buckets.computeIfAbsent(sub, key -> newBucket());
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    // Per-bucket evaluation counter (#1041 item 19) — every attempt, so rejections/requests gives
    // the per-subject rejection ratio. Bounded `subject` literal, never the JWT sub itself.
    meterRegistry
        .counter(MetricNames.RATELIMIT_REQUESTS, MetricNames.TAG_BUCKET, MetricNames.BUCKET_SUBJECT)
        .increment();
    if (!probe.isConsumed()) {
      // Per-subject 429 (REQ-OBS-011). Labelled by the bounded `subject` bucket literal, never the
      // JWT sub itself (PII/unbounded).
      meterRegistry
          .counter(
              MetricNames.RATELIMIT_REJECTIONS, MetricNames.TAG_BUCKET, MetricNames.BUCKET_SUBJECT)
          .increment();
      long retryAfterSeconds =
          Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
      // WARN, and deliberately the only rate-limit line at that level: this budget is per
      // authenticated subject, so its volume is bounded by the number of real users and every hit
      // is actionable ("why is my extractor being throttled?"). The subject itself is already in
      // the `userId` MDC field (REQ-OBS-001) and is never repeated into the message. Absence of
      // this line on a 429 therefore means the coarse per-IP limiter rejected the request — that
      // one stays at DEBUG because an attacker controls how often it fires.
      log.warn(
          "Per-subject ingest rate limit exceeded (capacity={} per {}, retryAfter={}s)",
          properties.getCapacity(),
          properties.getRefillPeriod(),
          retryAfterSeconds);
      throw new RateLimitedException(retryAfterSeconds);
    }
  }

  /**
   * Builds a fresh per-subject bucket from the shared {@code app.rate-limit} capacity / refill
   * budget.
   *
   * @return a new token bucket.
   */
  private @NotNull Bucket newBucket() {
    Bandwidth limit =
        Bandwidth.builder()
            .capacity(properties.getCapacity())
            .refillGreedy(properties.getRefillTokens(), properties.getRefillPeriod())
            .build();
    return Bucket.builder().addLimit(limit).build();
  }
}

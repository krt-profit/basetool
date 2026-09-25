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

package de.greluc.krt.profit.basetool.ingest.filter;

import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.config.RateLimitProperties;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.ratelimit.RateLimitBuckets;
import de.greluc.krt.profit.basetool.ingest.web.ProblemResponseWriter;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-client-IP token-bucket rate limiter for the ingest endpoints; an exhausted bucket yields 429
 * with {@code Retry-After} (REQ-INGEST-005).
 *
 * <p>A coarse front line with a looser budget than the per-subject {@link
 * de.greluc.krt.profit.basetool.ingest.ratelimit.SubjectRateLimiter}, since several members may
 * share one IP. The IP comes from trusted-proxy forwarded headers; the bucket map is bounded.
 */
@Slf4j
@Component
@Order(RateLimitingFilter.ORDER)
@RequiredArgsConstructor
public class RateLimitingFilter extends OncePerRequestFilter {

  /**
   * Runs after the correlation-id, bot and access-log filters and before {@link
   * PayloadSizeLimitFilter}, so a throttled request costs no body read.
   */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 20;

  /** Hard cap on simultaneously-tracked source IPs, bounding the bucket map's memory footprint. */
  static final int MAX_TRACKED_IPS = 50_000;

  private final Map<String, Bucket> buckets = RateLimitBuckets.boundedLru(MAX_TRACKED_IPS);

  /** The per-IP budget ({@code ipCapacity} / {@code ipRefillTokens} / {@code refillPeriod}). */
  private final RateLimitProperties properties;

  /** Serializes the 429 problem body. */
  private final ObjectMapper objectMapper;

  /** Counts every bucket evaluation and rejection under the bounded {@code ip} bucket label. */
  private final MeterRegistry meterRegistry;

  /** Supplies the MDC key the problem body's {@code correlationId} is read from. */
  private final LoggingProperties loggingProperties;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    Bucket bucket =
        buckets.computeIfAbsent(
            clientIp(request),
            ip ->
                RateLimitBuckets.newBucket(
                    properties.ipCapacity(),
                    properties.ipRefillTokens(),
                    properties.refillPeriod()));
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    meterRegistry
        .counter(MetricNames.RATELIMIT_REQUESTS, MetricNames.TAG_BUCKET, MetricNames.BUCKET_IP)
        .increment();
    if (!probe.isConsumed()) {
      meterRegistry
          .counter(MetricNames.RATELIMIT_REJECTIONS, MetricNames.TAG_BUCKET, MetricNames.BUCKET_IP)
          .increment();
      long retryAfterSeconds =
          Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
      log.debug(
          "Per-IP ingest rate limit exceeded (capacity={} per {}, retryAfter={}s)",
          properties.ipCapacity(),
          properties.refillPeriod(),
          retryAfterSeconds);
      response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
      ProblemResponseWriter.write(
          response,
          objectMapper,
          loggingProperties,
          HttpStatus.TOO_MANY_REQUESTS,
          "Rate limit exceeded",
          "RATE_LIMITED",
          "Too many ingest requests. Please retry later.");
      return;
    }
    filterChain.doFilter(request, response);
  }

  /**
   * Skips paths outside {@code /v1}, decided on the decoded path via {@link IngestPathScope}, and
   * everything when rate limiting is disabled.
   *
   * @param request the current request
   * @return {@code true} to bypass the filter
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    return !properties.enabled() || !IngestPathScope.isIngestRequest(request);
  }

  /**
   * Returns the servlet remote address, already resolved from trusted forwarded headers, as the
   * bucket key.
   *
   * @param request the current request
   * @return a non-null IP string usable as a map key
   */
  private static @NotNull String clientIp(@NotNull HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    return remote == null || remote.isBlank() ? "unknown" : remote;
  }
}

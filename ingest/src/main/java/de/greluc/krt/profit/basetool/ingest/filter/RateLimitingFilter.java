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

import de.greluc.krt.profit.basetool.ingest.config.RateLimitProperties;
import de.greluc.krt.profit.basetool.ingest.metrics.MetricNames;
import de.greluc.krt.profit.basetool.ingest.ratelimit.RateLimitBuckets;
import de.greluc.krt.profit.basetool.ingest.web.ProblemResponseWriter;
import io.github.bucket4j.Bandwidth;
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
import org.jetbrains.annotations.NotNull;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Per-client-IP token-bucket rate limiter for the ingest endpoints (REQ-INGEST-005). The new
 * ingress must not be usable to hammer the backend's import endpoints, so each source IP gets a
 * small bucket; an exhausted bucket yields 429 with a {@code Retry-After}. Mirrors the backend's
 * bucket4j approach.
 *
 * <p>This filter runs before the security chain and keys on the source IP, which a caller can
 * influence via {@code X-Forwarded-For}. It is therefore only a coarse front line; the enforceable
 * throttle is the per-subject {@link
 * de.greluc.krt.profit.basetool.ingest.ratelimit.SubjectRateLimiter}, which keys on the unforgeable
 * JWT {@code sub}. The {@code native} forward-headers strategy (see {@code application.yml}) routes
 * the IP through Tomcat's {@code RemoteIpValve}, which only honours {@code X-Forwarded-For} from
 * trusted internal proxies, so an external client cannot trivially spoof an arbitrary IP. The
 * bucket map is bounded ({@link RateLimitBuckets#boundedLru(int)}) so a flood of distinct IPs
 * cannot grow it without limit (security audit INGEST-RATELIMIT-1).
 */
@Component
@Order(RateLimitingFilter.ORDER)
@RequiredArgsConstructor
public class RateLimitingFilter extends OncePerRequestFilter {

  /** After correlation id and size cap, still before Spring Security. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 30;

  /** Hard cap on simultaneously-tracked source IPs, bounding the bucket map's memory footprint. */
  static final int MAX_TRACKED_IPS = 50_000;

  private final Map<String, Bucket> buckets = RateLimitBuckets.boundedLru(MAX_TRACKED_IPS);
  private final RateLimitProperties properties;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    Bucket bucket = buckets.computeIfAbsent(clientIp(request), ip -> newBucket());
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    // Per-bucket evaluation counter (#1041 item 19) — every attempt, so rejections/requests gives
    // the per-IP rejection ratio rather than 429-only detection. Bounded `ip` literal, not the IP.
    meterRegistry
        .counter(MetricNames.RATELIMIT_REQUESTS, MetricNames.TAG_BUCKET, MetricNames.BUCKET_IP)
        .increment();
    if (!probe.isConsumed()) {
      // Pre-auth per-IP 429 (REQ-OBS-011). Labelled by the bounded `ip` bucket literal, never the
      // client IP itself (PII/unbounded).
      meterRegistry
          .counter(MetricNames.RATELIMIT_REJECTIONS, MetricNames.TAG_BUCKET, MetricNames.BUCKET_IP)
          .increment();
      long retryAfterSeconds =
          Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()));
      response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
      ProblemResponseWriter.write(
          response,
          objectMapper,
          HttpStatus.TOO_MANY_REQUESTS,
          "Rate limit exceeded",
          "RATE_LIMITED",
          "Too many ingest requests. Please retry later.");
      return;
    }
    filterChain.doFilter(request, response);
  }

  /**
   * Skips paths outside {@code /v1/} and disables the filter entirely when rate limiting is off.
   *
   * @param request the current request
   * @return {@code true} to bypass the filter
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    return !properties.isEnabled() || !request.getRequestURI().startsWith("/v1/");
  }

  /**
   * Builds a fresh per-IP bucket from the configured capacity / refill.
   *
   * @return a new token bucket
   */
  private @NotNull Bucket newBucket() {
    Bandwidth limit =
        Bandwidth.builder()
            .capacity(properties.getCapacity())
            .refillGreedy(properties.getRefillTokens(), properties.getRefillPeriod())
            .build();
    return Bucket.builder().addLimit(limit).build();
  }

  /**
   * Resolves the client key for bucketing — the servlet remote address, which Spring resolves from
   * the forwarded headers under {@code forward-headers-strategy=framework}.
   *
   * @param request the current request
   * @return a non-null IP string usable as a map key
   */
  private static @NotNull String clientIp(@NotNull HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    return remote == null || remote.isBlank() ? "unknown" : remote;
  }
}

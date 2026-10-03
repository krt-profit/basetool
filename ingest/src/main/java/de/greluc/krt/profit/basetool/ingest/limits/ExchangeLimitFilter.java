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

package de.greluc.krt.profit.basetool.ingest.limits;

import de.greluc.krt.profit.basetool.ingest.auth.ExchangeTokenGateFilter;
import de.greluc.krt.profit.basetool.ingest.config.ExchangeLimitProperties;
import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.contract.ExchangeRoutes;
import de.greluc.krt.profit.basetool.ingest.edge.RateLimitBuckets;
import de.greluc.krt.profit.basetool.ingest.gate.ExchangeRequestContext;
import de.greluc.krt.profit.basetool.ingest.observability.ExchangeRefusals;
import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import de.greluc.krt.profit.basetool.ingest.problem.ProblemResponseWriter;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeUnavailableException;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeQuotas;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * The exchange's limits, after the registry gate (REQ-XCH-023): a per-minute bucket per client and
 * one per client and member in-process, an hourly bucket for the account check, and a daily write
 * quota in Redis. A limited request is {@code 429 RATE_LIMITED} or {@code 429 QUOTA_EXCEEDED} with
 * {@code Retry-After}; every admitted answer carries {@code RateLimit} and {@code RateLimit-Policy}
 * for the member's bucket.
 */
public class ExchangeLimitFilter extends OncePerRequestFilter {

  /** The response header naming the member bucket's state. */
  public static final String RATE_LIMIT = "RateLimit";

  /** The response header naming the member bucket's policy. */
  public static final String RATE_LIMIT_POLICY = "RateLimit-Policy";

  /** What a client waits before retrying when the quota cannot be counted. */
  static final String UNAVAILABLE_RETRY_AFTER_SECONDS = "30";

  private static final Duration MINUTE = Duration.ofMinutes(1);
  private static final Duration HOUR = Duration.ofHours(1);

  private final ExchangeLimitProperties properties;
  private final ExchangeQuotas quotas;
  private final ExchangeRefusals refusals;
  private final ObjectMapper objectMapper;
  private final LoggingProperties loggingProperties;
  private final MeterRegistry meterRegistry;
  private final Map<String, Bucket> buckets;

  /**
   * Creates the filter.
   *
   * @param properties the default limits
   * @param quotas the daily write counters
   * @param refusals counts the refusals
   * @param objectMapper writes the problems
   * @param loggingProperties names the correlation id
   * @param meterRegistry counts the errors
   */
  public ExchangeLimitFilter(
      @NotNull ExchangeLimitProperties properties,
      @NotNull ExchangeQuotas quotas,
      @NotNull ExchangeRefusals refusals,
      @NotNull ObjectMapper objectMapper,
      @NotNull LoggingProperties loggingProperties,
      @NotNull MeterRegistry meterRegistry) {
    this.properties = properties;
    this.quotas = quotas;
    this.refusals = refusals;
    this.objectMapper = objectMapper;
    this.loggingProperties = loggingProperties;
    this.meterRegistry = meterRegistry;
    this.buckets = RateLimitBuckets.boundedLru(properties.trackedBuckets());
  }

  /**
   * Applies the limits to an admitted request.
   *
   * @param request the request
   * @param response the response
   * @param filterChain the rest of the chain
   * @throws ServletException if a later filter fails
   * @throws IOException if writing fails
   */
  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain filterChain)
      throws ServletException, IOException {
    ExchangeRequestContext context = ExchangeRequestContext.of(request);
    Optional<ExchangeRoutes.Route> route =
        ExchangeRoutes.find(request.getMethod(), request.getRequestURI());
    if (context == null || route.isEmpty()) {
      filterChain.doFilter(request, response);
      return;
    }
    Integer override = context.client().requestsPerMinute();
    int memberLimit = override == null ? properties.memberPerMinute() : override;
    ConsumptionProbe member =
        bucket(
                "m|" + context.clientId() + "|" + context.member() + "|" + memberLimit,
                memberLimit,
                MINUTE)
            .tryConsumeAndReturnRemaining(1);
    if (!member.isConsumed()) {
      rateLimited(context.clientId(), response, member);
      return;
    }
    ConsumptionProbe client =
        bucket("c|" + context.clientId(), properties.clientPerMinute(), MINUTE)
            .tryConsumeAndReturnRemaining(1);
    if (!client.isConsumed()) {
      rateLimited(context.clientId(), response, client);
      return;
    }
    if (route.get().accountCheck()) {
      ConsumptionProbe check =
          bucket(
                  "a|" + context.clientId() + "|" + context.member(),
                  properties.accountChecksPerHour(),
                  HOUR)
              .tryConsumeAndReturnRemaining(1);
      if (!check.isConsumed()) {
        rateLimited(context.clientId(), response, check);
        return;
      }
    }
    response.setHeader(RATE_LIMIT_POLICY, memberLimit + ";w=60");
    response.setHeader(
        RATE_LIMIT,
        "limit="
            + memberLimit
            + ", remaining="
            + member.getRemainingTokens()
            + ", reset="
            + secondsUntilFull(memberLimit, member.getRemainingTokens()));
    if (route.get().write() && !withinQuota(context, request, response)) {
      return;
    }
    filterChain.doFilter(request, response);
  }

  /**
   * Counts a write against the member's daily quota and notes the counter on the request as {@link
   * ExchangeQuotas#COUNTED}, so a later refusal can give the count back.
   *
   * @param context the admitted request
   * @param request the request the counter is noted on
   * @param response the response a refusal is written to
   * @return {@code true} when the write is within the quota
   * @throws IOException if writing a refusal fails
   */
  private boolean withinQuota(
      @NotNull ExchangeRequestContext context,
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response)
      throws IOException {
    Integer override = context.client().writesPerDay();
    int limit = override == null ? properties.writesPerDay() : override;
    long count;
    try {
      ExchangeQuotas.Counted counted = quotas.countWrite(context.clientId(), context.member());
      request.setAttribute(ExchangeQuotas.COUNTED, counted.key());
      count = counted.count();
    } catch (ExchangeUnavailableException e) {
      response.setHeader(HttpHeaders.RETRY_AFTER, UNAVAILABLE_RETRY_AFTER_SECONDS);
      refuse(
          context.clientId(),
          response,
          HttpStatus.SERVICE_UNAVAILABLE,
          ExchangeRefusals.SERVICE_UNAVAILABLE,
          "The write quota cannot be counted; try again later.");
      return false;
    }
    if (count > limit) {
      response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(quotas.secondsUntilTomorrow()));
      refuse(
          context.clientId(),
          response,
          HttpStatus.TOO_MANY_REQUESTS,
          ExchangeRefusals.QUOTA_EXCEEDED,
          "The daily write quota is exhausted.");
      return false;
    }
    return true;
  }

  /**
   * Gates admitted exchange requests only; the documents and everything outside are left alone.
   *
   * @param request the current request
   * @return {@code true} to bypass the limits
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    return ExchangeTokenGateFilter.isUngated(request);
  }

  /**
   * Returns the bucket of a key, creating it on first use.
   *
   * @param key the bucket key
   * @param capacity the tokens per period
   * @param period the refill period
   * @return the bucket
   */
  private @NotNull Bucket bucket(@NotNull String key, int capacity, @NotNull Duration period) {
    return buckets.computeIfAbsent(
        key, k -> RateLimitBuckets.newBucket(capacity, capacity, period));
  }

  /**
   * Refuses a request over a per-period limit.
   *
   * @param client the admitted request's registry client id
   * @param response the response
   * @param probe the exhausted bucket's probe
   * @throws IOException if writing fails
   */
  private void rateLimited(
      @NotNull String client,
      @NotNull HttpServletResponse response,
      @NotNull ConsumptionProbe probe)
      throws IOException {
    long retryAfter =
        Math.max(1L, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
    refuse(
        client,
        response,
        HttpStatus.TOO_MANY_REQUESTS,
        ExchangeRefusals.RATE_LIMITED,
        "Too many requests; honour Retry-After.");
  }

  /**
   * Writes and counts one refusal.
   *
   * @param client the {@code client_id} label of the refused request
   * @param response the response
   * @param status the status
   * @param code the problem code
   * @param detail the detail
   * @throws IOException if writing fails
   */
  private void refuse(
      @NotNull String client,
      @NotNull HttpServletResponse response,
      @NotNull HttpStatus status,
      @NotNull String code,
      @NotNull String detail)
      throws IOException {
    refusals.count(code, client);
    meterRegistry.counter(MetricNames.HTTP_ERROR, MetricNames.TAG_CODE, code).increment();
    ProblemResponseWriter.write(
        response, objectMapper, loggingProperties, status, "Refused", code, detail);
  }

  /**
   * Estimates the seconds until a minute bucket is full again.
   *
   * @param capacity the bucket's capacity
   * @param remaining the tokens left
   * @return the seconds, zero when full
   */
  static long secondsUntilFull(int capacity, long remaining) {
    long missing = Math.max(0L, capacity - remaining);
    return (missing * MINUTE.toSeconds() + capacity - 1) / capacity;
  }
}

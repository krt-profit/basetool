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

package de.greluc.krt.profit.basetool.backend.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.AuthenticatedSubject;
import de.greluc.krt.profit.basetool.backend.support.ProblemResponseFactory;
import de.greluc.krt.profit.basetool.backend.support.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.server.PathContainer;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import tools.jackson.databind.ObjectMapper;

/**
 * Bounds how hard one authenticated account can drive the API, keyed on the JWT {@code sub}
 * (REQ-SEC-033).
 *
 * <p>Every {@code /api/**} write and the notification and live-sync stream connects share one
 * per-subject bucket; export, statement, report and PDF endpoints ({@link #EXPORT_SEGMENTS}) spend
 * from a separate, tighter bucket, and an export that is also a write spends from both. Other reads
 * and anonymous requests pass through to the per-IP limiter.
 */
@Slf4j
public class SubjectRateLimitingFilter extends OncePerRequestFilter {

  /** Stable machine-readable code on the problem body, shared with the per-IP limiter. */
  static final String CODE_RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED";

  /** Hard cap on simultaneously tracked subjects, bounding the bucket map's footprint. */
  static final long MAX_TRACKED_SUBJECTS = 50_000L;

  /** Idle expiry for a subject's bucket; a returning caller simply gets a fresh full budget. */
  private static final Duration BUCKET_EXPIRE_AFTER_ACCESS = Duration.ofHours(1);

  /** The paginated, mutating surface this filter guards. */
  private static final PathPattern API_SCOPE = PathPatternParser.defaultInstance.parse("/api/**");

  /** The one read that is not cheap: it holds a server-side emitter open for the caller. */
  private static final PathPattern SSE_CONNECT =
      PathPatternParser.defaultInstance.parse("/api/v1/notifications/stream");

  /**
   * The app's live-sync stream (ADR-0143), counted like {@link #SSE_CONNECT} although it is a GET.
   */
  private static final PathPattern LIVE_SYNC_CONNECT =
      PathPatternParser.defaultInstance.parse("/api/v1/live-sync/stream");

  /**
   * Path segments that mark an expensive document-rendering endpoint: any {@code /api/**} path
   * carrying one of them, compared decoded, spends from the export budget.
   */
  static final Set<String> EXPORT_SEGMENTS =
      Set.of("export", "export.json", "statement", "report", "pdf", "three-month-report");

  /** Correlation id echoed onto the problem body, matching the other filter-level problems. */
  private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  private final RateLimitProperties properties;
  private final MessageSource messageSource;
  private final ProblemResponseFactory problemResponseFactory;
  private final ObjectMapper objectMapper;
  private final MeterRegistry meterRegistry;
  private final Cache<String, Bucket> buckets;
  private final Cache<String, Bucket> exportBuckets;

  /**
   * Builds the filter and its bounded bucket cache.
   *
   * @param properties supplies the per-subject budget; never {@code null}.
   * @param messageSource localizes the 429 title and detail.
   * @param problemResponseFactory builds the RFC 7807 body.
   * @param objectMapper serialises that body.
   * @param meterRegistry carries the attempt and rejection counters.
   */
  public SubjectRateLimitingFilter(
      @NotNull RateLimitProperties properties,
      @NotNull MessageSource messageSource,
      @NotNull ProblemResponseFactory problemResponseFactory,
      @NotNull ObjectMapper objectMapper,
      @NotNull MeterRegistry meterRegistry) {
    this.properties = properties;
    this.messageSource = messageSource;
    this.problemResponseFactory = problemResponseFactory;
    this.objectMapper = objectMapper;
    this.meterRegistry = meterRegistry;
    this.buckets =
        Caffeine.newBuilder()
            .expireAfterAccess(BUCKET_EXPIRE_AFTER_ACCESS)
            .maximumSize(MAX_TRACKED_SUBJECTS)
            .build();
    this.exportBuckets =
        Caffeine.newBuilder()
            .expireAfterAccess(BUCKET_EXPIRE_AFTER_ACCESS)
            .maximumSize(MAX_TRACKED_SUBJECTS)
            .build();
  }

  /**
   * Restricts the filter to the surface the budget is for.
   *
   * <p>The scope is matched against the <b>decoded</b> path (REQ-SEC-029): {@code getRequestURI()}
   * is percent-encoded, so an encoded spelling must not shed the budget.
   *
   * @param request the incoming request.
   * @return {@code true} when the request is neither an API write, an SSE connect nor an export.
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    if (!properties.enabled() || !properties.subject().enabled()) {
      return true;
    }
    String uri = request.getRequestURI();
    if (uri == null) {
      return true;
    }
    PathContainer path = PathContainer.parsePath(uri);
    if (!API_SCOPE.matches(path)) {
      return true;
    }
    return !spendsFromWriteBudget(request.getMethod(), path) && !spendsFromExportBudget(path);
  }

  /**
   * Whether a request spends from the shared write/SSE bucket.
   *
   * @param method the request method; may be {@code null} for a malformed request.
   * @param path the decoded-matchable request path, already known to be under {@code /api/**}.
   * @return {@code true} for an API write or one of the two stream connects.
   */
  private static boolean spendsFromWriteBudget(String method, @NotNull PathContainer path) {
    return isWrite(method) || SSE_CONNECT.matches(path) || LIVE_SYNC_CONNECT.matches(path);
  }

  /**
   * Whether a request spends from the export bucket: the budget is switched on and the path is an
   * export by {@link #isExportPath(PathContainer)}.
   *
   * @param path the decoded-matchable request path, already known to be under {@code /api/**}.
   * @return {@code true} when the export budget applies to this path.
   */
  private boolean spendsFromExportBudget(@NotNull PathContainer path) {
    return properties.subject().export().enabled() && isExportPath(path);
  }

  /**
   * Answers whether a path names a document-rendering endpoint: one of its segments, decoded and
   * stripped of matrix parameters, is in {@link #EXPORT_SEGMENTS}.
   *
   * @param path the request path.
   * @return {@code true} when any segment marks an export.
   */
  static boolean isExportPath(@NotNull PathContainer path) {
    return path.elements().stream()
        .filter(PathContainer.PathSegment.class::isInstance)
        .map(PathContainer.PathSegment.class::cast)
        .anyMatch(segment -> EXPORT_SEGMENTS.contains(segment.valueToMatch()));
  }

  @Override
  protected void doFilterInternal(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull FilterChain chain)
      throws ServletException, IOException {
    Optional<String> subject =
        AuthenticatedSubject.of(SecurityContextHolder.getContext().getAuthentication());
    if (subject.isEmpty()) {
      chain.doFilter(request, response);
      return;
    }

    PathContainer path = PathContainer.parsePath(request.getRequestURI());
    if (spendsFromExportBudget(path)
        && !tryConsume(
            request,
            response,
            exportBuckets.get(subject.get(), key -> newExportBucket()),
            MetricNames.BUCKET_SUBJECT_EXPORT,
            properties.subject().export().capacity(),
            properties.subject().export().refillPeriod())) {
      return;
    }
    if (spendsFromWriteBudget(request.getMethod(), path)
        && !tryConsume(
            request,
            response,
            buckets.get(subject.get(), key -> newBucket()),
            MetricNames.BUCKET_SUBJECT,
            properties.subject().capacity(),
            properties.subject().refillPeriod())) {
      return;
    }
    chain.doFilter(request, response);
  }

  /**
   * Spends one token from {@code bucket}, counting the attempt, and writes the 429 when it is
   * empty.
   *
   * @param request the request being budgeted.
   * @param response the response, written only on a rejection.
   * @param bucket the subject's bucket for this budget.
   * @param bucketLabel the bounded {@code bucket} metric label of this budget.
   * @param capacity the budget's capacity, reported in the rejection headers and log line.
   * @param refillPeriod the budget's refill window, reported in the rejection log line.
   * @return {@code true} when a token was spent and the request may continue.
   * @throws IOException if the rejection body cannot be written.
   */
  private boolean tryConsume(
      @NotNull HttpServletRequest request,
      @NotNull HttpServletResponse response,
      @NotNull Bucket bucket,
      @NotNull String bucketLabel,
      int capacity,
      @NotNull Duration refillPeriod)
      throws IOException {
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
    meterRegistry
        .counter(MetricNames.RATELIMIT_REQUESTS, MetricNames.TAG_BUCKET, bucketLabel)
        .increment();
    if (probe.isConsumed()) {
      return true;
    }
    reject(
        request,
        response,
        TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()),
        bucketLabel,
        capacity,
        refillPeriod);
    return false;
  }

  /**
   * Whether a method mutates.
   *
   * @param method the request method; may be {@code null} for a malformed request.
   * @return {@code true} for the four mutating verbs.
   */
  private static boolean isWrite(String method) {
    return HttpMethod.POST.matches(method)
        || HttpMethod.PUT.matches(method)
        || HttpMethod.PATCH.matches(method)
        || HttpMethod.DELETE.matches(method);
  }

  /**
   * Creates a bucket carrying one subject's configured budget.
   *
   * @return a fresh full bucket.
   */
  private Bucket newBucket() {
    RateLimitProperties.Subject budget = properties.subject();
    return Bucket.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(budget.capacity())
                .refillGreedy(budget.refillTokens(), budget.refillPeriod())
                .build())
        .build();
  }

  /**
   * Creates a bucket carrying one subject's export budget.
   *
   * @return a fresh full export bucket.
   */
  private Bucket newExportBucket() {
    RateLimitProperties.Export budget = properties.subject().export();
    return Bucket.builder()
        .addLimit(
            Bandwidth.builder()
                .capacity(budget.capacity())
                .refillGreedy(budget.refillTokens(), budget.refillPeriod())
                .build())
        .build();
  }

  /**
   * Writes the 429, mirroring the per-IP limiter's header and body contract.
   *
   * @param request the refused request.
   * @param response the response to write.
   * @param retryAfterSeconds seconds until the subject's bucket refills enough for one more call.
   * @param bucketLabel the bounded {@code bucket} label of the budget that refused.
   * @param capacity that budget's capacity, reported as {@code X-Rate-Limit-Limit}.
   * @param refillPeriod that budget's refill window, for the log line.
   * @throws IOException if the body cannot be written.
   */
  private void reject(
      HttpServletRequest request,
      HttpServletResponse response,
      long retryAfterSeconds,
      String bucketLabel,
      int capacity,
      Duration refillPeriod)
      throws IOException {
    long retryAfter = Math.max(1, retryAfterSeconds);
    meterRegistry
        .counter(MetricNames.RATELIMIT_REJECTIONS, MetricNames.TAG_BUCKET, bucketLabel)
        .increment();
    log.warn(
        "Per-subject rate limit exceeded (bucket={}, capacity={} per {}, retryAfter={}s)",
        bucketLabel,
        capacity,
        refillPeriod,
        retryAfter);

    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader("X-Rate-Limit-Limit", String.valueOf(capacity));
    response.setHeader("X-Rate-Limit-Remaining", "0");
    response.setHeader("X-Rate-Limit-Retry-After-Seconds", String.valueOf(retryAfter));
    String correlationId = response.getHeader(CORRELATION_ID_HEADER);
    Locale locale = request.getLocale();
    String title =
        messageSource.getMessage("problem.rate_limit.title", null, "Too Many Requests", locale);
    String detail =
        messageSource.getMessage(
            "problem.rate_limit.detail", new Object[] {retryAfter}, "Rate limit exceeded.", locale);
    ProblemDetail problem =
        problemResponseFactory.problem(
            HttpStatus.TOO_MANY_REQUESTS,
            title,
            detail,
            request.getRequestURI(),
            "rate-limit-exceeded",
            CODE_RATE_LIMIT_EXCEEDED,
            correlationId);
    response.getOutputStream().write(objectMapper.writeValueAsBytes(problem));
  }
}

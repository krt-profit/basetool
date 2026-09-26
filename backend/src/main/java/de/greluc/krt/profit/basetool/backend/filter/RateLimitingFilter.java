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

package de.greluc.krt.profit.basetool.backend.filter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.AppProblemProperties;
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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.util.pattern.PatternParseException;
import tools.jackson.core.io.JsonStringEncoder;

/**
 * Per-client token-bucket rate limiter using Bucket4j buckets in a bounded Caffeine cache.
 *
 * <p>Applies only to the patterns in {@code app.rate-limit.paths} and the endpoint-specific rules,
 * checking the tightest matching budget first. The client address comes from {@link
 * ClientIpContextFilter}; the address is never logged or exported, only its {@link KeySource}.
 * Rejections get a 429 with a localized RFC&nbsp;7807 body and {@code X-Rate-Limit-*} headers.
 */
@Slf4j
public class RateLimitingFilter extends OncePerRequestFilter {

  private static final Duration BUCKET_EXPIRE_AFTER_ACCESS = Duration.ofHours(1);
  private static final long BUCKET_MAX_ENTRIES = 100_000L;

  /**
   * Stable machine-readable error code echoed in the 429 body, mirroring GlobalExceptionHandler.
   */
  private static final String CODE_RATE_LIMIT_EXCEEDED = "RATE_LIMIT_EXCEEDED";

  /**
   * App-wide correlation-id response header, hardcoded to avoid a {@code filter -> config} package
   * cycle (ADR-0047).
   */
  private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  private final RateLimitProperties properties;
  private final AppProblemProperties problemProperties;
  private final MessageSource messageSource;
  private final PathPatternParser pathPatternParser = new PathPatternParser();
  private final Map<String, Optional<PathPattern>> compiledPatterns = new ConcurrentHashMap<>();
  private final Cache<String, Bucket> bucketCache;
  private final MeterRegistry meterRegistry;

  /**
   * Creates the filter and compiles every configured rate-limit pattern, failing startup on a
   * malformed one.
   *
   * @param properties bucket capacity/refill configuration, path patterns and endpoint rules
   * @param problemProperties RFC&nbsp;7807 problem-type base URI for the 429 body
   * @param messageSource resolves the localized 429 {@code title}/{@code detail}
   * @param meterRegistry registry for the 429 rejection counter
   * @throws IllegalStateException when a configured pattern is blank or invalid {@link PathPattern}
   *     syntax
   */
  public RateLimitingFilter(
      RateLimitProperties properties,
      AppProblemProperties problemProperties,
      MessageSource messageSource,
      MeterRegistry meterRegistry) {
    this.properties = properties;
    this.problemProperties = problemProperties;
    this.messageSource = messageSource;
    this.meterRegistry = meterRegistry;
    this.bucketCache =
        Caffeine.newBuilder()
            .expireAfterAccess(BUCKET_EXPIRE_AFTER_ACCESS)
            .maximumSize(BUCKET_MAX_ENTRIES)
            .build();
    precompileAndValidatePatterns(properties);
  }

  /**
   * Compiles every global and per-rule rate-limit pattern into {@link #compiledPatterns} at
   * construction, failing fast on a blank or unparseable one.
   *
   * @param properties the validated rate-limit configuration
   * @throws IllegalStateException when a configured pattern is blank or invalid PathPattern syntax
   */
  private void precompileAndValidatePatterns(@NotNull RateLimitProperties properties) {
    List<String> globalPaths = properties.paths();
    if (globalPaths != null) {
      for (String pattern : globalPaths) {
        compileOrFail(pattern, "app.rate-limit.paths");
      }
    }
    List<RateLimitProperties.Rule> rules = properties.rules();
    if (rules != null) {
      for (RateLimitProperties.Rule rule : rules) {
        List<String> rulePaths = rule.paths();
        if (rulePaths != null) {
          for (String pattern : rulePaths) {
            compileOrFail(pattern, "app.rate-limit.rules[" + rule.name() + "].paths");
          }
        }
      }
    }
  }

  /**
   * Compiles one configured pattern into {@link #compiledPatterns}.
   *
   * @param rawPattern the raw pattern to compile and cache
   * @param origin the configuration key the pattern came from, named in the failure message
   * @throws IllegalStateException when {@code rawPattern} is blank or cannot be parsed
   */
  private void compileOrFail(String rawPattern, String origin) {
    if (rawPattern == null || rawPattern.isBlank()) {
      throw new IllegalStateException(
          "Blank rate-limit path pattern configured under "
              + origin
              + "; every pattern must be a non-blank PathPattern.");
    }
    try {
      compiledPatterns.put(rawPattern, Optional.of(pathPatternParser.parse(rawPattern)));
    } catch (PatternParseException ex) {
      throw new IllegalStateException(
          "Invalid rate-limit path pattern '"
              + rawPattern
              + "' configured under "
              + origin
              + "; PathPattern allows ** only as the final segment. Reason: "
              + ex.getMessage(),
          ex);
    }
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!properties.enabled()) {
      return true;
    }
    String path = request.getRequestURI();
    List<String> patterns = properties.paths();
    if (patterns == null || patterns.isEmpty()) {
      return true;
    }
    PathContainer parsedPath = PathContainer.parsePath(path);
    for (String pattern : patterns) {
      if (matches(pattern, parsedPath)) {
        return false;
      }
    }
    return true;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    ClientKey clientKey = resolveClientKey(request);
    List<BucketSlot> slots = resolveSlots(request);
    if (slots.isEmpty()) {
      filterChain.doFilter(request, response);
      return;
    }

    slots.sort(Comparator.comparingInt(BucketSlot::capacity).thenComparing(BucketSlot::key));

    int tightestLimit = Integer.MAX_VALUE;
    long tightestRemaining = Long.MAX_VALUE;
    for (BucketSlot slot : slots) {
      Bucket bucket =
          bucketCache.get(clientKey.key() + "|" + slot.key(), k -> createNewBucket(slot));
      ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
      meterRegistry
          .counter(MetricNames.RATELIMIT_REQUESTS, MetricNames.TAG_BUCKET, bucketLabel(slot.key()))
          .increment();
      if (!probe.isConsumed()) {
        meterRegistry
            .counter(
                MetricNames.RATELIMIT_REJECTIONS,
                MetricNames.TAG_BUCKET,
                bucketLabel(slot.key()),
                MetricNames.TAG_KEY_SOURCE,
                clientKey.source().label())
            .increment();
        long nanosToWait = probe.getNanosToWaitForRefill();
        long secondsToWait = (long) Math.ceil(nanosToWait / 1_000_000_000.0);
        String correlationId = UUID.randomUUID().toString();
        log.debug(
            "Rate limit exceeded: keySource={}, slot={}, path={}, retryAfterSeconds={},"
                + " correlationId={}",
            clientKey.source().label(),
            slot.key(),
            request.getRequestURI(),
            secondsToWait,
            correlationId);
        writeTooManyRequests(response, request, slot.capacity(), secondsToWait, correlationId);
        return;
      }
      if (slot.capacity() < tightestLimit) {
        tightestLimit = slot.capacity();
        tightestRemaining = probe.getRemainingTokens();
      }
    }

    response.setHeader("X-Rate-Limit-Limit", String.valueOf(tightestLimit));
    response.setHeader("X-Rate-Limit-Remaining", String.valueOf(tightestRemaining));
    filterChain.doFilter(request, response);
  }

  /**
   * Builds the list of bucket slots that apply to the current request — the global default (when
   * the path matches {@link RateLimitProperties#getPaths()}) plus every endpoint-specific rule that
   * matches both the request URI AND the HTTP method. Order is irrelevant here; the caller sorts
   * tightest-first before consumption.
   */
  @NotNull
  private List<BucketSlot> resolveSlots(@NotNull HttpServletRequest request) {
    String path = request.getRequestURI();
    String method = request.getMethod();
    PathContainer parsedPath = PathContainer.parsePath(path);
    List<BucketSlot> slots = new ArrayList<>();

    String globalPattern = firstMatchingPattern(parsedPath, properties.paths());
    if (globalPattern != null) {
      slots.add(
          new BucketSlot(
              "path:" + globalPattern,
              properties.capacity(),
              properties.refillTokens(),
              properties.refillPeriod()));
    }

    List<RateLimitProperties.Rule> rules = properties.rules();
    if (rules != null) {
      for (RateLimitProperties.Rule rule : rules) {
        if (matchesMethod(rule.methods(), method) && matchesAnyPath(rule.paths(), parsedPath)) {
          slots.add(
              new BucketSlot(
                  "rule:" + rule.name(),
                  rule.capacity(),
                  rule.refillTokens(),
                  rule.refillPeriod()));
        }
      }
    }

    return slots;
  }

  /**
   * {@code true} when the rule's HTTP-method allowlist is empty (any method allowed) or contains
   * the request's method (case-insensitive). Mirrors how Spring's {@code @RequestMapping(method =
   * …)} treats an empty array.
   */
  private static boolean matchesMethod(List<String> ruleMethods, String requestMethod) {
    if (ruleMethods == null || ruleMethods.isEmpty()) {
      return true;
    }
    for (String m : ruleMethods) {
      if (m != null && m.equalsIgnoreCase(requestMethod)) {
        return true;
      }
    }
    return false;
  }

  private boolean matchesAnyPath(List<String> patterns, PathContainer parsedPath) {
    if (patterns == null) {
      return false;
    }
    for (String p : patterns) {
      if (matches(p, parsedPath)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns whether {@code rawPattern} matches the parsed request path, compiling and caching the
   * pattern on first use.
   *
   * @param rawPattern the raw pattern from configuration (e.g. {@code /api/**})
   * @param parsedPath the request path, parsed once per request
   * @return {@code true} when the pattern matches; {@code false} otherwise or when it failed to
   *     compile
   */
  private boolean matches(String rawPattern, PathContainer parsedPath) {
    return compiledPatterns
        .computeIfAbsent(rawPattern, this::tryParse)
        .map(pattern -> pattern.matches(parsedPath))
        .orElse(false);
  }

  /**
   * Compiles one raw pattern, logging at ERROR and returning empty when it is invalid; a runtime
   * fallback for patterns changed after construction.
   *
   * @param rawPattern the raw pattern to compile
   * @return the compiled pattern, or empty when it could not be parsed
   */
  @NotNull
  private Optional<PathPattern> tryParse(String rawPattern) {
    try {
      return Optional.of(pathPatternParser.parse(rawPattern));
    } catch (PatternParseException ex) {
      log.error(
          "Invalid app.rate-limit path pattern '{}' encountered at runtime; it will never match, "
              + "leaving the endpoint unprotected. Reason: {}",
          rawPattern,
          ex.getMessage());
      return Optional.empty();
    }
  }

  /**
   * Resolves the rate-limit key for the request from the address published by {@link
   * ClientIpContextFilter}, together with the {@link KeySource} that produced it.
   *
   * @param request the request whose bucket key is derived
   * @return the bucket key plus its source; never {@code null}
   */
  @NotNull
  private ClientKey resolveClientKey(@NotNull HttpServletRequest request) {
    Object resolved = request.getAttribute(ClientIpContextFilter.CLIENT_IP_ATTRIBUTE);
    if (resolved instanceof String ip && !ip.isBlank()) {
      boolean forwarded =
          Boolean.TRUE.equals(
              request.getAttribute(ClientIpContextFilter.CLIENT_IP_FORWARDED_ATTRIBUTE));
      return new ClientKey(ip, forwarded ? KeySource.FORWARDED : KeySource.PEER);
    }
    return new ClientKey(request.getRemoteAddr(), KeySource.PEER);
  }

  @Nullable
  private String firstMatchingPattern(PathContainer parsedPath, List<String> patterns) {
    if (patterns == null) {
      return null;
    }
    for (String p : patterns) {
      if (matches(p, parsedPath)) {
        return p;
      }
    }
    return null;
  }

  private Bucket createNewBucket(@NotNull BucketSlot slot) {
    Bandwidth limit =
        Bandwidth.builder()
            .capacity(slot.capacity())
            .refillGreedy(slot.refillTokens(), slot.refillPeriod())
            .build();
    return Bucket.builder().addLimit(limit).build();
  }

  private void writeTooManyRequests(
      @NotNull HttpServletResponse response,
      HttpServletRequest request,
      int rejectedLimit,
      long retryAfterSeconds,
      String correlationId)
      throws IOException {
    response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
    response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
    response.setHeader("X-Rate-Limit-Limit", String.valueOf(rejectedLimit));
    response.setHeader("X-Rate-Limit-Remaining", "0");
    response.setHeader("X-Rate-Limit-Retry-After-Seconds", String.valueOf(retryAfterSeconds));
    response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds));
    response.setHeader(CORRELATION_ID_HEADER, correlationId);

    Locale locale = request.getLocale();
    String title =
        messageSource.getMessage("problem.rate_limit.title", null, "Too Many Requests", locale);
    String detail =
        messageSource.getMessage(
            "problem.rate_limit.detail",
            new Object[] {String.valueOf(retryAfterSeconds)},
            "Rate limit exceeded. Try again in " + retryAfterSeconds + " seconds.",
            locale);

    String instanceEscaped = jsonEscape(request.getRequestURI());
    String titleEscaped = jsonEscape(title);
    String detailEscaped = jsonEscape(detail);
    String correlationIdEscaped = jsonEscape(correlationId);
    String body =
        "{"
            + "\"type\":\""
            + problemProperties.baseUri()
            + "rate-limit-exceeded\","
            + "\"title\":\""
            + titleEscaped
            + "\","
            + "\"status\":"
            + HttpStatus.TOO_MANY_REQUESTS.value()
            + ","
            + "\"detail\":\""
            + detailEscaped
            + "\","
            + "\"instance\":\""
            + instanceEscaped
            + "\","
            + "\"code\":\""
            + CODE_RATE_LIMIT_EXCEEDED
            + "\","
            + "\"correlationId\":\""
            + correlationIdEscaped
            + "\""
            + "}";
    response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Escapes a string into a JSON string-literal body (without quotes) via {@link
   * JsonStringEncoder}.
   *
   * @param raw the raw value to escape
   * @return the JSON-escaped value, ready to be wrapped in double quotes
   */
  private static String jsonEscape(String raw) {
    StringBuilder builder = new StringBuilder();
    JsonStringEncoder.getInstance().quoteAsString(raw, builder);
    return builder.toString();
  }

  /**
   * Per-request snapshot of one rate-limit slot: the bucket-key suffix and its bandwidth
   * parameters.
   *
   * @param key bucket-key suffix combined with the client IP (e.g. {@code rule:mission-create})
   * @param capacity max tokens for this slot
   * @param refillTokens tokens added per {@link #refillPeriod()}
   * @param refillPeriod time window for the {@link #refillTokens()} refill
   */
  private record BucketSlot(String key, int capacity, int refillTokens, Duration refillPeriod) {}

  /**
   * The resolved rate-limit identity of one request: the bucket-key prefix and its {@link
   * KeySource}.
   *
   * @param key the bucket-key prefix (the client address); never logged or exported (REQ-OBS-004)
   * @param source which resolution branch produced {@code key}; safe to log and export
   */
  private record ClientKey(String key, KeySource source) {}

  /**
   * Where a {@link ClientKey#key()} came from. Exactly two values, so it is safe as a metric tag
   * (REQ-OBS-006) and readable in a log line without exposing an address.
   */
  private enum KeySource {

    /**
     * The key was resolved from a trusted proxy's {@code X-Forwarded-For} chain — the first
     * untrusted hop walking from the right, i.e. the address the proxy appended.
     */
    FORWARDED(MetricNames.KEY_SOURCE_FORWARDED),

    /**
     * The key is the immediate peer address, because the peer is not a trusted proxy or sent no
     * usable {@code X-Forwarded-For}. Sustained rejections on this value behind a reverse proxy
     * mean every client shares one bucket.
     */
    PEER(MetricNames.KEY_SOURCE_PEER);

    private final String label;

    /**
     * Binds the constant to the wire label shared by the metric tag and the log line.
     *
     * @param label the {@code MetricNames} literal exported for this branch
     */
    KeySource(String label) {
      this.label = label;
    }

    /**
     * Returns the wire label ({@code forwarded} / {@code peer}) used both as the {@code key_source}
     * metric tag value and in the 429 DEBUG line, so dashboards and logs agree on the spelling.
     *
     * @return the bounded label literal
     */
    String label() {
      return label;
    }
  }

  /**
   * Reduces a {@link BucketSlot#key()} to the bounded {@code bucket} metric label: the rule name
   * for a {@code rule:} slot, {@link MetricNames#BUCKET_GLOBAL} otherwise (REQ-OBS-006).
   *
   * @param slotKey the {@code prefix:suffix} slot key
   * @return the rule name, or {@link MetricNames#BUCKET_GLOBAL}
   */
  private static String bucketLabel(@NotNull String slotKey) {
    int separator = slotKey.indexOf(':');
    if (separator >= 0 && "rule".equals(slotKey.substring(0, separator))) {
      return slotKey.substring(separator + 1);
    }
    return MetricNames.BUCKET_GLOBAL;
  }
}

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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.PathContainer;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import org.springframework.web.util.pattern.PatternParseException;
import tools.jackson.core.io.JsonStringEncoder;

/**
 * Per-IP token-bucket rate limiter implemented with Bucket4j buckets in a Caffeine cache.
 *
 * <p>Active only on URI patterns listed in {@code app.rate-limit.paths} and disabled wholesale via
 * {@code app.rate-limit.enabled=false}. Patterns are matched with Spring's {@link
 * org.springframework.web.util.pattern.PathPattern} (parsed once and cached per pattern) rather
 * than {@code AntPathMatcher} re-tokenizing on every request; {@code **} is therefore only valid as
 * the final segment, which all configured patterns already are. Every configured pattern is
 * compiled and validated once at filter construction, so a malformed pattern (e.g. a mid-path
 * {@code **}) aborts application startup with a precise message instead of silently leaving the
 * matching endpoints unprotected at runtime. Buckets are keyed by {@code clientIp + "|" + slot}
 * where {@code slot} is either {@code "path:<pattern>"} for the global default or {@code
 * "rule:<name>"} for an endpoint-specific rule (audit finding L-5, 2026-05-20). The Caffeine cache
 * expires entries after one hour of inactivity (1h hibernate-window keeps abusive clients limited
 * across short pauses) and is capped at 100 000 entries to bound memory under a Slowloris-style
 * attack.
 *
 * <p>When both the global default and one or more endpoint-specific rules match, the filter
 * iterates them tightest-first and aborts on the first depleted bucket — so spam against the
 * anonymous-reachable POST endpoints (mission create, joborder create, finance-entry create,
 * participant CRUD) trips its per-endpoint budget before the loose global budget is touched. The
 * {@code X-Rate-Limit-Limit} / {@code X-Rate-Limit-Remaining} response headers reflect the tightest
 * matching budget on the happy path.
 *
 * <p>{@code X-Forwarded-For} is only honored when the immediate peer is listed in {@code
 * app.rate-limit.trusted-proxies}; blanket trust (the literal {@code "*"}) is explicitly rejected
 * because it would let any client spoof the header and get a fresh bucket per request. Which of the
 * two branches produced the bucket key is carried as {@link KeySource} on the {@link ClientKey} and
 * exported both as the {@code key_source} tag of the rejection counter and as {@code keySource=} in
 * the DEBUG line — never the address itself. That is what makes a 429 spike readable: {@code peer}
 * keys behind the edge mean the trusted-proxy list drifted and every user collapsed onto one shared
 * budget (the 2026-07-06 recurrence), {@code forwarded} keys mean individual callers tripped their
 * own. The 429 short-circuits before {@code RequestLoggingFilter}, so there is no access-log line
 * to fall back on. Logging the raw client IP instead is not an option: it is PII the backend stdout
 * stream's 744h retention is explicitly predicated on never carrying (REQ-OBS-004).
 *
 * <p>Rejected requests get a 429 with an RFC&nbsp;7807 body and rate-limit headers ({@code
 * X-Rate-Limit-Limit}, {@code X-Rate-Limit-Remaining}, {@code X-Rate-Limit-Retry-After-Seconds}).
 * The body mirrors GlobalExceptionHandler's contract: a stable {@code code} of {@code
 * RATE_LIMIT_EXCEEDED}, a per-response {@code correlationId} (also logged) for traceability, and a
 * {@code title}/{@code detail} localized from the request's {@code Accept-Language} rather than
 * hardcoded English.
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
   * App-wide correlation-id response header. Hardcoded rather than read from {@code
   * LoggingProperties} on purpose: that config bean lives in the {@code config} package and {@code
   * RateLimitingConfig} already constructs this filter ({@code config -> filter}), so a {@code
   * filter -> config} dependency would close a package cycle (ADR-0047). The name mirrors {@code
   * LoggingProperties}' default; it is a wire constant, not a per-deployment override.
   */
  private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  private final RateLimitProperties properties;
  private final AppProblemProperties problemProperties;
  private final MessageSource messageSource;
  private final PathPatternParser pathPatternParser = new PathPatternParser();
  private final Map<String, Optional<PathPattern>> compiledPatterns = new ConcurrentHashMap<>();
  private final Cache<String, Bucket> bucketCache;
  private final List<IpAddressMatcher> trustedProxyMatchers;
  private final MeterRegistry meterRegistry;

  /**
   * Constructs the filter with the bucket cache sized from compile-time constants ({@code 1h}
   * idle-expiry, 100 000 entries max). Both properties classes carry the validated
   * {@code @ConfigurationProperties} values pulled from {@code application.yml}. The trusted-proxy
   * list is compiled into {@link IpAddressMatcher} instances once during construction so that the
   * per-request {@link #resolveClientKey} hot path no longer allocates a fresh matcher (and
   * re-parses the CIDR / IP literal) on every call. Malformed entries are logged once here and
   * dropped from the cached list, exactly as the previous per-request path did. Every configured
   * rate-limit pattern is likewise compiled and validated here via {@link
   * #precompileAndValidatePatterns(RateLimitProperties)}, so a malformed pattern fails startup
   * instead of silently disabling enforcement at runtime.
   *
   * @param properties bucket capacity/refill configuration plus path patterns, endpoint-specific
   *     rules and trusted proxies
   * @param problemProperties RFC&nbsp;7807 problem-type base URI used in the 429 response body
   * @param messageSource resolves the localized 429 {@code title}/{@code detail} from the request's
   *     {@code Accept-Language}, mirroring GlobalExceptionHandler's i18n so the rate limiter is no
   *     longer the only RFC&nbsp;7807 producer emitting hardcoded English
   * @param meterRegistry the Micrometer registry the per-bucket 429 rejection counter is bound to
   * @throws IllegalStateException when any configured rate-limit pattern is blank or not valid
   *     {@link PathPattern} syntax
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
    this.trustedProxyMatchers = compileTrustedProxies(properties.getTrustedProxies());
    precompileAndValidatePatterns(properties);
  }

  /**
   * Eagerly compiles every configured rate-limit pattern — the global {@link
   * RateLimitProperties#getPaths()} umbrella plus every {@link RateLimitProperties.Rule#getPaths()
   * rule pattern} — at filter construction. This serves two purposes: it warms {@link
   * #compiledPatterns} so no request pays the first-compile cost, and, crucially, it FAILS FAST — a
   * pattern that {@link PathPattern} cannot parse (e.g. a mid-path {@code **}) or a blank entry
   * aborts application startup with a message naming the offending pattern and its config origin,
   * rather than silently degrading to a permanent non-match at runtime and leaving the matching
   * endpoints unprotected. Because {@code app.rate-limit} configuration is bound once at startup,
   * this validation covers every pattern the filter will ever evaluate; the {@link
   * #tryParse(String)} runtime fallback survives only as defense-in-depth for a pattern set mutated
   * after construction.
   *
   * @param properties the validated rate-limit configuration whose patterns are compiled
   * @throws IllegalStateException when any configured pattern is blank or not valid PathPattern
   *     syntax
   */
  private void precompileAndValidatePatterns(RateLimitProperties properties) {
    List<String> globalPaths = properties.getPaths();
    if (globalPaths != null) {
      for (String pattern : globalPaths) {
        compileOrFail(pattern, "app.rate-limit.paths");
      }
    }
    List<RateLimitProperties.Rule> rules = properties.getRules();
    if (rules != null) {
      for (RateLimitProperties.Rule rule : rules) {
        List<String> rulePaths = rule.getPaths();
        if (rulePaths != null) {
          for (String pattern : rulePaths) {
            compileOrFail(pattern, "app.rate-limit.rules[" + rule.getName() + "].paths");
          }
        }
      }
    }
  }

  /**
   * Compiles one configured pattern and stores it in {@link #compiledPatterns}, throwing {@link
   * IllegalStateException} (which aborts context startup) when it is blank or not valid {@link
   * PathPattern} syntax. The thrown message names both the offending pattern and the {@code origin}
   * config key so an operator can fix the typo without grepping; the cause carries the underlying
   * {@link PatternParseException} for the full parser diagnostic.
   *
   * @param rawPattern the raw pattern to compile and cache
   * @param origin the configuration key the pattern came from, for the failure message (e.g. {@code
   *     app.rate-limit.paths})
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

  /**
   * Compiles each entry of the trusted-proxies list into an {@link IpAddressMatcher} once at filter
   * construction. Blank entries and the literal {@code "*"} blanket-trust sentinel are explicitly
   * excluded (the latter would let any client spoof {@code X-Forwarded-For}); malformed CIDR / IP
   * literals are logged at WARN and dropped so a configuration typo cannot disable the rest of the
   * trusted list. Returns an unmodifiable list because the field is shared across request threads.
   */
  private static List<IpAddressMatcher> compileTrustedProxies(List<String> entries) {
    if (entries == null || entries.isEmpty()) {
      return List.of();
    }
    List<IpAddressMatcher> matchers = new ArrayList<>(entries.size());
    for (String entry : entries) {
      if (entry == null || entry.isBlank() || "*".equals(entry)) {
        continue;
      }
      try {
        matchers.add(new IpAddressMatcher(entry));
      } catch (IllegalArgumentException ex) {
        log.warn(
            "Invalid app.rate-limit.trusted-proxies entry '{}'; ignoring. Reason: {}",
            entry,
            ex.getMessage());
      }
    }
    return Collections.unmodifiableList(matchers);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    if (!properties.isEnabled()) {
      return true;
    }
    String path = request.getRequestURI();
    List<String> patterns = properties.getPaths();
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
      // The umbrella {@code paths} match was confirmed by {@link #shouldNotFilter}; if no slots
      // survive the rule walk that means none of the configured limits apply to this request
      // (e.g. a future config that lists rules but no global path-pattern). Pass through.
      filterChain.doFilter(request, response);
      return;
    }

    // Tightest first: a spam attack on an anonymous endpoint trips the per-endpoint budget before
    // the loose global budget is debited. Tie-break on slot key so the iteration order is
    // deterministic when two rules share a capacity — important for the 429-header attribution.
    slots.sort(Comparator.comparingInt(BucketSlot::capacity).thenComparing(BucketSlot::key));

    int tightestLimit = Integer.MAX_VALUE;
    long tightestRemaining = Long.MAX_VALUE;
    for (BucketSlot slot : slots) {
      Bucket bucket =
          bucketCache.get(clientKey.key() + "|" + slot.key(), k -> createNewBucket(slot));
      ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
      // Per-bucket evaluation counter (#1041 item 19) — every attempt, consumed or not, so
      // rejections/requests yields a rejection ratio rather than 429-only detection.
      meterRegistry
          .counter(MetricNames.RATELIMIT_REQUESTS, MetricNames.TAG_BUCKET, bucketLabel(slot.key()))
          .increment();
      if (!probe.isConsumed()) {
        // Per-bucket 429 rejection counter (REQ-OBS-011). Both labels are bounded: the rule name
        // (or `global` for the umbrella path budget) derived from the slot key, and the two-valued
        // key source. Never the client IP or the request URI, both unbounded/PII. Without
        // key_source a 429 spike cannot be told apart — one abusive caller looks exactly like a
        // trusted-proxies drift that collapsed every user onto a single shared budget.
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
        // The rate limiter runs before CorrelationIdFilter, so no request-scoped correlation id
        // exists yet. Mint one here and thread it through both the log line and the response body
        // so a user reporting a 429 can be traced to this exact log entry.
        String correlationId = UUID.randomUUID().toString();
        // keySource, never the address: the raw client IP is PII this log stream must not carry
        // (REQ-OBS-004), and the branch that produced the key is the part that is actually
        // diagnostic. Stays at DEBUG — the global bucket is anonymous-reachable, so an attacker
        // could otherwise flood the log at will.
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
  private List<BucketSlot> resolveSlots(HttpServletRequest request) {
    String path = request.getRequestURI();
    String method = request.getMethod();
    PathContainer parsedPath = PathContainer.parsePath(path);
    List<BucketSlot> slots = new ArrayList<>();

    String globalPattern = firstMatchingPattern(parsedPath, properties.getPaths());
    if (globalPattern != null) {
      slots.add(
          new BucketSlot(
              "path:" + globalPattern,
              properties.getCapacity(),
              properties.getRefillTokens(),
              properties.getRefillPeriod()));
    }

    List<RateLimitProperties.Rule> rules = properties.getRules();
    if (rules != null) {
      for (RateLimitProperties.Rule rule : rules) {
        if (matchesMethod(rule.getMethods(), method)
            && matchesAnyPath(rule.getPaths(), parsedPath)) {
          slots.add(
              new BucketSlot(
                  "rule:" + rule.getName(),
                  rule.getCapacity(),
                  rule.getRefillTokens(),
                  rule.getRefillPeriod()));
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
   * Returns whether {@code rawPattern} matches the already-parsed request {@code parsedPath},
   * compiling the raw pattern into a {@link PathPattern} on first use and caching the result. The
   * pattern set is bounded by {@code app.rate-limit} configuration (the global {@code paths} plus
   * the per-rule {@code paths}), so the cache cannot grow with request volume; this replaces the
   * per-request re-tokenization of pattern <i>and</i> path that {@code
   * AntPathMatcher.match(pattern, path)} performed on every call. The request path is parsed into a
   * {@link PathContainer} once per request by the caller and reused across all pattern checks.
   *
   * @param rawPattern the raw Ant-style pattern from configuration (e.g. {@code /api/**})
   * @param parsedPath the request path parsed once per request
   * @return {@code true} when the compiled pattern matches the path; {@code false} when it does not
   *     or when the pattern failed to compile (see {@link #tryParse(String)})
   */
  private boolean matches(String rawPattern, PathContainer parsedPath) {
    return compiledPatterns
        .computeIfAbsent(rawPattern, this::tryParse)
        .map(pattern -> pattern.matches(parsedPath))
        .orElse(false);
  }

  /**
   * Compiles one raw rate-limit pattern into a {@link PathPattern}, returning {@link
   * Optional#empty()} (and logging at ERROR) when the pattern is not valid PathPattern syntax.
   * PathPattern accepts {@code **} only as the final segment, whereas the previous {@code
   * AntPathMatcher} also tolerated mid-path {@code **}; routing a parse failure to a permanent
   * non-match keeps a configuration typo from turning every matching request into a 500.
   *
   * <p>This is the runtime fallback only — every pattern present at startup is already compiled and
   * validated by {@link #precompileAndValidatePatterns(RateLimitProperties)}, which fails the boot
   * outright. Reaching this branch therefore means the pattern set was mutated after construction
   * (e.g. a {@code @RefreshScope} rebind or a test) to something invalid, which silently disables
   * enforcement for that pattern — hence ERROR, not WARN.
   *
   * @param rawPattern the raw pattern to compile
   * @return the compiled pattern, or empty when it could not be parsed
   */
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
   * Resolves the rate-limit key for the incoming request <em>together with the branch that produced
   * it</em>. {@code X-Forwarded-For} is only honored when the immediate peer ({@code
   * request.getRemoteAddr()}) matches one of the trusted-proxy matchers compiled at filter
   * construction; the literal {@code "*"} is intentionally NOT a valid trust value, since blanket
   * trust lets any client spoof the header and obtain a fresh bucket per request.
   *
   * <p>The branch is returned rather than discarded because it is the only thing that makes a 429
   * spike interpretable without logging the address: {@link KeySource#PEER} behind a reverse proxy
   * means every client shares one bucket, {@link KeySource#FORWARDED} means per-client bucketing
   * works as designed.
   *
   * <p>Audit finding H-8: each trusted-proxies entry may be either an exact IP ({@code 172.17.0.1})
   * or a CIDR range ({@code 172.17.0.0/16}). Matching delegates to Spring Security's {@link
   * IpAddressMatcher} so docker-network ranges (typically {@code 172.17.0.0/16}, {@code
   * 10.0.0.0/8}, etc.) can be configured without enumerating every proxy IP individually.
   *
   * @param request the request whose bucket key is being derived
   * @return the bucket key plus the branch it came from; never {@code null}
   */
  private ClientKey resolveClientKey(HttpServletRequest request) {
    String remoteAddr = request.getRemoteAddr();
    boolean isTrusted = false;
    for (IpAddressMatcher matcher : trustedProxyMatchers) {
      if (matcher.matches(remoteAddr)) {
        isTrusted = true;
        break;
      }
    }

    if (isTrusted) {
      String xff = request.getHeader("X-Forwarded-For");
      if (xff != null && !xff.isBlank()) {
        // First IP in the list is the original client
        int idx = xff.indexOf(',');
        String forwarded = idx > 0 ? xff.substring(0, idx).trim() : xff.trim();
        // A header whose first element is empty (e.g. " , 1.2.3.4") would otherwise key every such
        // request on the empty string AND report `forwarded`, i.e. hide the very collapse this tag
        // exists to expose. Fall through to the peer address, which is always present.
        if (!forwarded.isEmpty()) {
          return new ClientKey(forwarded, KeySource.FORWARDED);
        }
      }
    }

    return new ClientKey(remoteAddr, KeySource.PEER);
  }

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

  private Bucket createNewBucket(BucketSlot slot) {
    Bandwidth limit =
        Bandwidth.builder()
            .capacity(slot.capacity())
            .refillGreedy(slot.refillTokens(), slot.refillPeriod())
            .build();
    return Bucket.builder().addLimit(limit).build();
  }

  private void writeTooManyRequests(
      HttpServletResponse response,
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
    // Echo the minted correlationId as the app-wide response header. This filter rejects before
    // CorrelationIdFilter runs, so that filter never gets to echo it — mirror its contract here so
    // a 429 response still carries X-Correlation-Id (RFC-7807 hardening, REQ-OBS).
    response.setHeader(CORRELATION_ID_HEADER, correlationId);

    // Localize title/detail from the request's Accept-Language (LocaleContextHolder is not yet
    // populated this early in the filter chain, so request.getLocale() is the authoritative source
    // here). Fall back to the hardcoded English strings when the bundle key is missing.
    Locale locale = request.getLocale();
    String title =
        messageSource.getMessage("problem.rate_limit.title", null, "Too Many Requests", locale);
    String detail =
        messageSource.getMessage(
            "problem.rate_limit.detail",
            new Object[] {String.valueOf(retryAfterSeconds)},
            "Rate limit exceeded. Try again in " + retryAfterSeconds + " seconds.",
            locale);

    // The {@code instance} field reflects the request URI, which is fully attacker-controlled.
    // String concatenation directly into a JSON literal would let a crafted path like
    // {@code /api/v1/x","fake":"injected} break out of the quoted value and append arbitrary
    // top-level fields, polluting the RFC 7807 contract a client may rely on (JSON-injection,
    // CodeQL: java/xss). Escape via Jackson's {@link JsonStringEncoder} so that {@code "},
    // {@code \}, control chars and unicode separators land as proper JSON escapes. The localized
    // title/detail are bundle-controlled, but escape them the same way for uniform safety.
    // Jackson 3 dropped the String -> char[] overload; quote into a StringBuilder instead.
    String instanceEscaped = jsonEscape(request.getRequestURI());
    String titleEscaped = jsonEscape(title);
    String detailEscaped = jsonEscape(detail);
    String correlationIdEscaped = jsonEscape(correlationId);
    String body =
        "{"
            + "\"type\":\""
            + problemProperties.getBaseUri()
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
    // Write UTF-8 bytes directly rather than through getWriter(): the localized title/detail may
    // contain non-ASCII (e.g. German umlauts) and the servlet writer defaults to ISO-8859-1, which
    // would mangle them. Going through the byte stream keeps the Content-Type as the app-standard
    // application/problem+json (no ;charset= suffix) while still emitting valid UTF-8 JSON.
    response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Escapes a raw string into a JSON string-literal body (without surrounding quotes) via Jackson's
   * {@link JsonStringEncoder}, so quotes, backslashes, control characters and unicode separators
   * cannot break out of the quoted value in the hand-built RFC&nbsp;7807 document.
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
   * Per-request snapshot of one rate-limit slot — the data needed to look up or create the
   * corresponding Bucket4j bucket. Carries the bucket key suffix, the bandwidth parameters and
   * nothing else; the surrounding filter sorts {@link #capacity()} ascending so the tightest budget
   * is consumed first.
   *
   * @param key bucket-key suffix combined with the client IP (e.g. {@code rule:mission-create})
   * @param capacity max tokens for this slot
   * @param refillTokens tokens added per {@link #refillPeriod()}
   * @param refillPeriod time window for the {@link #refillTokens()} refill
   */
  private record BucketSlot(String key, int capacity, int refillTokens, Duration refillPeriod) {}

  /**
   * The resolved rate-limit identity of one request: the bucket-key prefix and the branch of {@link
   * #resolveClientKey} that produced it.
   *
   * <p>Keeping the two together is the point — {@link #key()} is a client IP and therefore must
   * never leave this filter (REQ-OBS-004), while {@link #source()} is a two-valued, bounded label
   * that is safe to log and to export as a metric tag. Splitting them at the resolution site makes
   * it hard to accidentally log the address while reaching for the diagnosis.
   *
   * @param key the bucket-key prefix (the client address); never logged or exported
   * @param source which branch produced {@code key}
   */
  private record ClientKey(String key, KeySource source) {}

  /**
   * Where a {@link ClientKey#key()} came from. Exactly two values, so it is safe as a metric tag
   * (REQ-OBS-006) and readable in a log line without exposing an address.
   */
  private enum KeySource {

    /** The key is the first entry of a trusted proxy's {@code X-Forwarded-For} header. */
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
   * Reduces a {@link BucketSlot#key()} to the bounded {@code bucket} metric label. A {@code
   * rule:<name>} slot yields its configured rule name (e.g. {@code mission-create}); the umbrella
   * {@code path:/api/**} slot (and any other non-rule slot) collapses to the fixed {@link
   * MetricNames#BUCKET_GLOBAL} literal so the label never embeds a raw path pattern. Keeping the
   * value space to the four configured rule names plus {@code global} bounds the metric's
   * cardinality (REQ-OBS-006).
   *
   * @param slotKey the {@code prefix:suffix} slot key
   * @return the bounded rule name, or {@link MetricNames#BUCKET_GLOBAL}
   */
  private static String bucketLabel(String slotKey) {
    int separator = slotKey.indexOf(':');
    if (separator >= 0 && "rule".equals(slotKey.substring(0, separator))) {
      return slotKey.substring(separator + 1);
    }
    return MetricNames.BUCKET_GLOBAL;
  }
}

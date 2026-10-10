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

package de.greluc.krt.profit.basetool.ingest.idempotency;

import de.greluc.krt.profit.basetool.ingest.auth.ExchangeTokenGateFilter;
import de.greluc.krt.profit.basetool.ingest.config.ExchangeStoreProperties;
import de.greluc.krt.profit.basetool.ingest.config.LoggingProperties;
import de.greluc.krt.profit.basetool.ingest.contract.ExchangeRoutes;
import de.greluc.krt.profit.basetool.ingest.edge.CachedBodyRequest;
import de.greluc.krt.profit.basetool.ingest.gate.ExchangeRequestContext;
import de.greluc.krt.profit.basetool.ingest.observability.ExchangeRefusals;
import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import de.greluc.krt.profit.basetool.ingest.problem.ProblemResponseWriter;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeUnavailableException;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeBudget;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeIdempotency;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeQuotas;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Makes exchange writes idempotent (REQ-XCH-020) within the byte budget (REQ-XCH-023). A write
 * needs an {@code Idempotency-Key}; its answer is kept a day per client, member and key and
 * replayed for the same request, a different request under the same key is refused, and a duplicate
 * while the first is in flight is refused with {@code 409 IDEMPOTENCY_IN_PROGRESS}. Under the claim
 * the cache is read again, so a request that raced the first one's answer replays it instead of
 * writing twice. The gates, limits and quota run before this filter, so a refused request is never
 * cached; neither is any {@code 401}, {@code 403}, {@code 429}, {@code 5xx}, a staged mass change
 * or the answer to a body that is not a JSON document ({@link #NOT_REPLAYABLE}). A write the byte
 * budget refuses gives its daily quota count back and is told to retry when enough of the budget
 * expires.
 */
public class ExchangeIdempotencyFilter extends OncePerRequestFilter {

  /** The request header naming the write. */
  public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  /** The response header marking a replayed answer. */
  public static final String REPLAYED = "Idempotency-Replayed";

  /**
   * The request attribute that keeps an answer out of the cache whatever its status, set for a body
   * that is not a JSON document.
   */
  public static final String NOT_REPLAYABLE =
      ExchangeIdempotencyFilter.class.getName() + ".notReplayable";

  /** The shape of an idempotency key. */
  private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._~-]{8,128}$");

  /** What a client waits before retrying a store refusal. */
  static final String RETRY_AFTER_SECONDS = "60";

  private final ExchangeIdempotency idempotency;
  private final ExchangeBudget budget;
  private final ExchangeQuotas quotas;
  private final ExchangeStoreProperties properties;
  private final ExchangeRefusals refusals;
  private final ObjectMapper objectMapper;
  private final LoggingProperties loggingProperties;
  private final MeterRegistry meterRegistry;

  /**
   * Creates the filter.
   *
   * @param idempotency the cache
   * @param budget the byte budget
   * @param quotas gives a budget refusal's quota count back
   * @param properties the cache's limits
   * @param refusals counts the refusals
   * @param objectMapper writes the problems and reads cached codes
   * @param loggingProperties names the correlation id
   * @param meterRegistry counts replays and errors
   */
  public ExchangeIdempotencyFilter(
      @NotNull ExchangeIdempotency idempotency,
      @NotNull ExchangeBudget budget,
      @NotNull ExchangeQuotas quotas,
      @NotNull ExchangeStoreProperties properties,
      @NotNull ExchangeRefusals refusals,
      @NotNull ObjectMapper objectMapper,
      @NotNull LoggingProperties loggingProperties,
      @NotNull MeterRegistry meterRegistry) {
    this.idempotency = idempotency;
    this.budget = budget;
    this.quotas = quotas;
    this.properties = properties;
    this.refusals = refusals;
    this.objectMapper = objectMapper;
    this.loggingProperties = loggingProperties;
    this.meterRegistry = meterRegistry;
    meterRegistry.counter(MetricNames.EXCHANGE_IDEMPOTENT_REPLAYS);
  }

  /**
   * Replays, refuses or runs and caches one write.
   *
   * @param request the write
   * @param response the response
   * @param filterChain the rest of the chain
   * @throws ServletException if a later filter fails
   * @throws IOException if reading or writing fails
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
    if (context == null || route.isEmpty() || !route.get().write()) {
      filterChain.doFilter(request, response);
      return;
    }
    String key = request.getHeader(IDEMPOTENCY_KEY);
    if (key == null || !KEY.matcher(key).matches()) {
      refuse(
          context.clientId(),
          response,
          HttpStatus.BAD_REQUEST,
          ExchangeRefusals.IDEMPOTENCY_KEY_MISSING,
          "A write needs an Idempotency-Key of 8 to 128 characters [A-Za-z0-9._~-].");
      return;
    }
    byte[] body = request.getInputStream().readAllBytes();
    String namespace = ExchangeIdempotency.namespace(context.clientId(), context.member(), key);
    String fingerprint =
        ExchangeIdempotency.fingerprint(request.getMethod(), request.getRequestURI(), body);
    String token;
    try {
      Optional<ExchangeIdempotency.Stored> stored = idempotency.find(namespace);
      if (stored.isPresent()) {
        replayOrRefuse(context.clientId(), response, stored.get(), fingerprint);
        return;
      }
      token = idempotency.claim(namespace).orElse(null);
    } catch (ExchangeUnavailableException _) {
      storeUnavailable(context.clientId(), response);
      return;
    }
    if (token == null) {
      refuse(
          context.clientId(),
          response,
          HttpStatus.CONFLICT,
          ExchangeRefusals.IDEMPOTENCY_IN_PROGRESS,
          "A request with this Idempotency-Key is still being processed.");
      return;
    }
    ContentCachingResponseWrapper wrapper = new ContentCachingResponseWrapper(response);
    try {
      runClaimed(
          new CachedBodyRequest(request, body),
          wrapper,
          filterChain,
          context,
          namespace,
          fingerprint);
    } finally {
      idempotency.releaseClaim(namespace, token);
      wrapper.copyBodyToResponse();
    }
  }

  /**
   * Runs one write under its key's claim: replays an answer another request cached meanwhile,
   * reserves the largest cacheable answer in the byte budget, then runs the write and settles the
   * reservation on the answer it cached, or frees it.
   *
   * @param request the write with its body
   * @param wrapper the response being recorded
   * @param filterChain the rest of the chain
   * @param context the admitted request
   * @param namespace the key's namespace
   * @param fingerprint the request's fingerprint
   * @throws ServletException if a later filter fails
   * @throws IOException if reading or writing fails
   */
  private void runClaimed(
      @NotNull HttpServletRequest request,
      @NotNull ContentCachingResponseWrapper wrapper,
      @NotNull FilterChain filterChain,
      @NotNull ExchangeRequestContext context,
      @NotNull String namespace,
      @NotNull String fingerprint)
      throws ServletException, IOException {
    String reservation = ExchangeIdempotency.CLAIM_PREFIX + namespace;
    long reserved = ExchangeIdempotency.claimBytes(namespace) + properties.maxResultBytes();
    try {
      Optional<ExchangeIdempotency.Stored> stored = idempotency.find(namespace);
      if (stored.isPresent()) {
        replayOrRefuse(context.clientId(), wrapper, stored.get(), fingerprint);
        return;
      }
      if (!budget.reserve(
          context.clientId(), context.member(), reservation, reserved, properties.lockTtl())) {
        quotas.refundCounted(request);
        wrapper.setHeader(
            HttpHeaders.RETRY_AFTER,
            String.valueOf(
                budget.retryAfterSeconds(context.clientId(), context.member(), reserved)));
        refuse(
            context.clientId(),
            wrapper,
            HttpStatus.SERVICE_UNAVAILABLE,
            ExchangeRefusals.EXCHANGE_BUDGET_EXHAUSTED,
            "The exchange's storage budget is full; try again later.");
        return;
      }
    } catch (ExchangeUnavailableException _) {
      storeUnavailable(context.clientId(), wrapper);
      return;
    }
    boolean settled = false;
    try {
      filterChain.doFilter(request, wrapper);
      settled =
          request.getAttribute(NOT_REPLAYABLE) == null
              && cacheIfAllowed(context, namespace, fingerprint, reservation, reserved, wrapper);
    } finally {
      if (!settled) {
        budget.release(context.clientId(), context.member(), reservation, reserved);
      }
    }
  }

  /**
   * Caches a finished answer when the contract and the byte budget allow it, in place of the
   * write's reservation.
   *
   * @param context the admitted request
   * @param namespace the key's namespace
   * @param fingerprint the request's fingerprint
   * @param reservation the reservation's key
   * @param reserved the reservation's size
   * @param wrapper the finished answer
   * @return {@code true} when the reservation was settled on the cached answer
   */
  private boolean cacheIfAllowed(
      @NotNull ExchangeRequestContext context,
      @NotNull String namespace,
      @NotNull String fingerprint,
      @NotNull String reservation,
      long reserved,
      @NotNull ContentCachingResponseWrapper wrapper) {
    byte[] content = wrapper.getContentAsByteArray();
    String body = new String(content, StandardCharsets.UTF_8);
    if (!cacheable(wrapper.getStatus(), body) || content.length > properties.maxResultBytes()) {
      return false;
    }
    ExchangeIdempotency.Stored stored =
        new ExchangeIdempotency.Stored(
            fingerprint, wrapper.getStatus(), wrapper.getContentType(), body);
    String key = ExchangeIdempotency.PREFIX + namespace;
    try {
      int size = idempotency.sizeOf(namespace, stored);
      if (!budget.settle(
          context.clientId(),
          context.member(),
          reservation,
          reserved,
          key,
          size,
          properties.idempotencyTtl())) {
        logger.warn("An exchange answer does not fit the budget and is not cached for replay");
        return false;
      }
      try {
        idempotency.store(namespace, stored);
      } catch (ExchangeUnavailableException _) {
        budget.release(context.clientId(), context.member(), key, size);
        logger.warn("An exchange answer could not be cached for replay");
      }
      return true;
    } catch (ExchangeUnavailableException _) {
      logger.warn("An exchange answer could not be cached for replay");
      return false;
    }
  }

  /**
   * Refuses a write whose idempotency store cannot be reached.
   *
   * @param client the admitted request's registry client id
   * @param response the response
   * @throws IOException if writing fails
   */
  private void storeUnavailable(@NotNull String client, @NotNull HttpServletResponse response)
      throws IOException {
    response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
    refuse(
        client,
        response,
        HttpStatus.SERVICE_UNAVAILABLE,
        ExchangeRefusals.SERVICE_UNAVAILABLE,
        "The idempotency store cannot be reached; try again later.");
  }

  /**
   * Whether an answer may be replayed: a success or a domain refusal, never an authentication,
   * permission, limit, availability or server failure, and never a staged mass change.
   *
   * @param status the status
   * @param body the body
   * @return {@code true} when it may be cached
   */
  boolean cacheable(int status, @NotNull String body) {
    if (status >= 200 && status < 300) {
      return true;
    }
    if (status != 400 && status != 404 && status != 409 && status != 410 && status != 422) {
      return false;
    }
    return !"MASS_CHANGE_CONFIRMATION_REQUIRED".equals(code(body));
  }

  /**
   * Replays a cached answer for the same request, or refuses a different one.
   *
   * @param client the admitted request's registry client id
   * @param response the response
   * @param stored the cached answer
   * @param fingerprint the current request's fingerprint
   * @throws IOException if writing fails
   */
  private void replayOrRefuse(
      @NotNull String client,
      @NotNull HttpServletResponse response,
      @NotNull ExchangeIdempotency.Stored stored,
      @NotNull String fingerprint)
      throws IOException {
    if (!stored.fingerprint().equals(fingerprint)) {
      refuse(
          client,
          response,
          HttpStatus.UNPROCESSABLE_CONTENT,
          ExchangeRefusals.IDEMPOTENCY_KEY_REUSED,
          "This Idempotency-Key was used for a different request.");
      return;
    }
    meterRegistry.counter(MetricNames.EXCHANGE_IDEMPOTENT_REPLAYS).increment();
    response.setStatus(stored.status());
    if (stored.contentType() != null) {
      response.setContentType(stored.contentType());
    }
    response.setHeader(REPLAYED, "true");
    byte[] body = stored.body().getBytes(StandardCharsets.UTF_8);
    response.setContentLength(body.length);
    response.getOutputStream().write(body);
  }

  /**
   * Reads the problem code of an answer body.
   *
   * @param body the body
   * @return the code, or {@code null}
   */
  private @Nullable String code(@NotNull String body) {
    if (body.isEmpty()) {
      return null;
    }
    try {
      JsonNode code = objectMapper.readTree(body).get("code");
      return code != null && code.isString() ? code.stringValue() : null;
    } catch (RuntimeException _) {
      return null;
    }
  }

  /**
   * Gates admitted exchange requests only.
   *
   * @param request the current request
   * @return {@code true} to bypass the filter
   */
  @Override
  protected boolean shouldNotFilter(@NotNull HttpServletRequest request) {
    return ExchangeTokenGateFilter.isUngated(request);
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
}

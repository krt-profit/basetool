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

package de.greluc.krt.profit.basetool.frontend.service;

import de.greluc.krt.profit.basetool.frontend.exception.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.TermsDocumentDto;
import de.greluc.krt.profit.basetool.frontend.support.CatalogPages;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * WebClient wrapper for the backend REST API. Centralises RFC-7807 problem-response parsing into
 * {@link BackendServiceException} and exposes typed convenience overloads for every HTTP verb.
 * {@code getCached(CachedCatalog, ...)} layers Spring Cache on top, routing each {@link
 * CachedCatalog} to its per-domain named cache via {@link CatalogCacheResolver} (FE-CACHE-1/2).
 *
 * <p><b>Resilience layering.</b> Every outbound call — regardless of HTTP verb — passes through the
 * {@link de.greluc.krt.profit.basetool.frontend.config.WebClientConfig#resilienceFilter WebClient
 * filter chain}, which applies the operators bulkhead → time limiter → retry (only on idempotent
 * verbs GET/HEAD/OPTIONS/TRACE, never on writes) → circuit breaker against the {@code backendApi}
 * Resilience4j instance. The filter-level {@link io.github.resilience4j.timelimiter.TimeLimiter}
 * therefore covers POST/PUT/PATCH/DELETE the same way it covers GET — there is no timeout gap on
 * state-changing calls. This filter chain is the <b>single</b> resilience pass: the formerly
 * present method-level {@code @Retry}/{@code @CircuitBreaker} AOP annotations (bound to a separate
 * {@code backend} Resilience4j instance) were removed because they wrapped every call a second time
 * — double-retrying each GET (up to 2×2 attempts) and tracking a parallel circuit-breaker window —
 * without adding the one thing that actually guards a hung upstream thread, the {@link
 * io.github.resilience4j.timelimiter.TimeLimiter}, which only the filter carries. Removing them
 * also makes a circuit-breaker-open on a write surface as a clean {@code 503} (the filter throws
 * {@link io.github.resilience4j.circuitbreaker.CallNotPermittedException} inside the reactive
 * chain, where {@code executePost} et al. map it) instead of escaping the AOP proxy unmapped.
 *
 * <p>Page controllers should call into this client and let {@link
 * de.greluc.krt.profit.basetool.frontend.exception.GlobalExceptionHandler} surface failures — do
 * not catch {@link BackendServiceException} on the call site.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BackendApiClient {

  private final WebClient webClient;

  /**
   * The bearer-less client for the Terms-of-Use wording, and nothing else (REQ-SEC-052).
   *
   * <p>Injected by name so the narrowing is enforced by the object graph rather than by a comment:
   * a second caller would have to ask for this bean explicitly, which {@code
   * TermsDocumentClientUsageTest} refuses.
   */
  private final WebClient termsDocumentClient;

  /** The path the wording lives at; the one URI {@link #termsDocumentClient} ever sees. */
  private static final String TERMS_DOCUMENT_URI = "/api/v1/terms/document";

  /**
   * Reads the Terms-of-Use wording in force, without a bearer token.
   *
   * <p>The one anonymous backend call the frontend makes (ADR-0138 / REQ-SEC-028, REQ-SEC-052). It
   * has to be anonymous because the public {@code /terms} page renders it for a visitor who has no
   * session, and it can be anonymous because the document is the same text that page publishes.
   *
   * <p>A named method rather than a boolean flag. Its predecessor was {@code get(uri, type, true)},
   * and the flag was passed at roughly forty call sites — every one of them a decision to send a
   * request with no identity, taken by typing {@code true}. Now there is one method, one client and
   * one URI, and {@code TermsDocumentClientUsageTest} asserts nothing else injects the bean.
   *
   * @return the wording in force, or {@code null} when the backend returned no body
   */
  public TermsDocumentDto getTermsDocumentAnonymously() {
    return executeGet(termsDocumentClient, TERMS_DOCUMENT_URI, TermsDocumentDto.class);
  }

  private final MeterRegistry meterRegistry;
  private final CacheManager cacheManager;

  /**
   * Dedicated ObjectMapper used exclusively to decode backend Problem+JSON bodies. Kept as an
   * internal instance — not auto-wired — because some frontend test slices run without Spring
   * Boot's JacksonAutoConfiguration and therefore without a shared ObjectMapper bean.
   */
  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  /**
   * Increments {@code basetool_backend_client_errors_total} for a failed backend call
   * (REQ-OBS-011). Both labels are bounded: {@code reason} is a fixed local enumeration (never the
   * backend's response-body code, which could be arbitrary), and {@code method} is the HTTP verb.
   * The request URI is deliberately never a label — it embeds ids/paths and is unbounded.
   *
   * @param reason the bounded failure reason ({@code MetricNames.REASON_*})
   * @param method the HTTP verb of the failed call
   */
  private void countBackendError(String reason, String method) {
    meterRegistry
        .counter(
            MetricNames.BACKEND_CLIENT_ERRORS,
            MetricNames.TAG_REASON,
            reason,
            MetricNames.TAG_METHOD,
            method)
        .increment();
  }

  /**
   * Whether a problem code is one of the access gates refusing an authenticated user, rather than a
   * backend-call failure.
   *
   * <p>All three are expected, high-frequency and self-clearing: a pending registration polls until
   * an admin approves it, an unconsented session hits the Terms-of-Use gate on every request until
   * it accepts, and a role-less account is refused on every call until an administrator assigns one
   * (REQ-SEC-017, REQ-SEC-028, REQ-SEC-053). None of them says anything about backend health, which
   * is what {@code basetool_backend_client_errors_total} is read as.
   *
   * <p>{@code NO_ROLE} joined the list on 2026-09-06, having been missed when it shipped: one
   * waiting account loading one page produced a WARN and an error-counter increment per fragment on
   * it — {@code /users/me}, terms status, capabilities, notifications, active org unit, org units,
   * mission search — which is the shape that made {@code BackendCallFailureSustained} fire on the
   * consent rollout and is exactly what excluding the other two exists to prevent.
   *
   * @param problemCode the RFC 7807 {@code code} the backend returned, may be {@code null}
   * @return {@code true} when the refusal is an expected access gate
   */
  private static boolean isExpectedAccessGateRefusal(String problemCode) {
    return BackendServiceException.CODE_PENDING_APPROVAL.equals(problemCode)
        || BackendServiceException.CODE_TERMS_NOT_ACCEPTED.equals(problemCode)
        || BackendServiceException.CODE_NO_ROLE.equals(problemCode);
  }

  /** GET against the authenticated backend, decoded via a {@link ParameterizedTypeReference}. */
  public <T> T get(String uri, ParameterizedTypeReference<T> responseType) {
    return executeGet(webClient, uri, responseType);
  }

  /**
   * GET against the authenticated backend, expanding {@code uriVariables} into {@code uriTemplate}
   * so the WebClient encodes them per RFC 3986. Prefer this over hand-encoding a value into the URI
   * string: a value carrying spaces or reserved characters (e.g. a normalized blueprint product key
   * such as {@code killshot "dominion camo" rifle}) round-trips intact, whereas {@code
   * URLEncoder.encode} form-encoding (space &rarr; {@code +}) gets mangled when re-encoded across
   * the frontend&rarr;backend hop. Targets the authenticated WebClient only.
   *
   * @param uriTemplate the URI template containing {@code {name}} placeholders
   * @param responseType the decoded response type
   * @param uriVariables the values expanded into the template, encoded by the WebClient
   * @param <T> the response body type
   * @return the decoded response body
   */
  public <T> T get(
      String uriTemplate, ParameterizedTypeReference<T> responseType, Object... uriVariables) {
    return executeGet(webClient, uriTemplate, responseType, uriVariables);
  }

  /** GET overload for simple (non-generic) return types. */
  public <T> T get(String uri, Class<T> responseType) {
    return executeGet(webClient, uri, responseType);
  }

  /**
   * Cached GET of a {@link CachedCatalog}. Subsequent calls within the catalogue's domain-cache TTL
   * hit the cache; the per-domain named cache is resolved by {@link CatalogCacheResolver}
   * (FE-CACHE-1/2). Only an allowlisted {@link CachedCatalog} can be cached, so a per-principal URI
   * is unrepresentable. A {@link CachedCatalog.Fetch#PAGE_WALK} catalogue is assembled complete —
   * every backend page walked and merged before the single cache write (REQ-ADMIN-003).
   *
   * @param catalog the allowlisted catalogue to fetch and cache
   * @param responseType the decoded response type
   * @param <T> the response body type
   * @return the decoded (possibly cached) response body
   */
  @Cacheable(cacheResolver = "catalogCacheResolver", key = "#catalog.name()", sync = true)
  public <T> T getCached(CachedCatalog catalog, ParameterizedTypeReference<T> responseType) {
    // The page walk is not an optimisation: a bounded single GET of a PAGE_WALK catalogue returns
    // its first chunk, and the cache then holds that truncated answer for the whole TTL
    // (REQ-ADMIN-003). A mid-walk failure propagates unchanged, so no partial catalogue is cached.
    if (catalog.isPageWalked()) {
      return fetchCompleteCatalog(catalog, responseType);
    }
    return executeGet(webClient, catalog.getUri(), responseType);
  }

  /**
   * Class-typed cached GET of a {@link CachedCatalog}.
   *
   * @param catalog the allowlisted catalogue to fetch and cache; must not be a {@link
   *     CachedCatalog.Fetch#PAGE_WALK} catalogue
   * @param responseType the decoded response type
   * @param <T> the response body type
   * @return the decoded (possibly cached) response body
   * @throws IllegalArgumentException when {@code catalog} is page-walked — a {@code Class} token
   *     cannot decode the generic {@code PageResponse} the walk requires, and a plain single-page
   *     GET of such a catalogue would silently truncate it (REQ-ADMIN-003)
   */
  @Cacheable(cacheResolver = "catalogCacheResolver", key = "#catalog.name()", sync = true)
  public <T> T getCached(CachedCatalog catalog, Class<T> responseType) {
    if (catalog.isPageWalked()) {
      throw new IllegalArgumentException(
          "Catalogue "
              + catalog.name()
              + " is page-walked and must be read through the ParameterizedTypeReference overload"
              + " of getCached — a single bounded GET would silently truncate it (REQ-ADMIN-003)");
    }
    return executeGet(webClient, catalog.getUri(), responseType);
  }

  /**
   * Assembles a {@link CachedCatalog.Fetch#PAGE_WALK} catalogue by walking every backend page
   * through {@link CatalogPages#fetchAll} ({@code &page=0..n} appended to the pinned URI, whose
   * {@code size=} is the chunk size) and merging the contents into one synthetic {@link
   * PageResponse} — the value the caller's {@code @Cacheable} frame then caches, so the cached
   * entry is always the complete catalogue. Hitting the {@link CatalogPages#MAX_CATALOG_PAGES}
   * runaway cap logs a warning instead of a banner: these catalogues feed pickers and sidebar
   * fragments with no page-level truncation surface (REQ-ADMIN-003).
   *
   * @param catalog the page-walked catalogue to assemble
   * @param responseType the caller's declared response type — always {@code PageResponse<E>} for a
   *     page-walked catalogue
   * @param <T> the caller's response body type
   * @return the merged catalogue as a single {@code PageResponse}
   */
  // A PAGE_WALK catalogue is always consumed as PageResponse<E> via the type-ref overload
  // (FrontendCacheSplitTest pins the modes; the Class overload rejects walked constants), so the
  // T <-> PageResponse casts below are the unavoidable Object->generic case.
  @SuppressWarnings("unchecked")
  private <T> T fetchCompleteCatalog(
      CachedCatalog catalog, ParameterizedTypeReference<T> responseType) {
    CatalogPages.CompleteCatalog<Object> walked =
        CatalogPages.fetchAll(
            page ->
                (PageResponse<Object>)
                    executeGet(webClient, catalog.getUri() + "&page=" + page, responseType));
    if (walked.truncated()) {
      log.warn(
          "Cached catalogue {} hit the page-walk safety cap of {} pages — the cached list is"
              + " incomplete until the catalogue shrinks or its chunk size grows",
          catalog.name(),
          CatalogPages.MAX_CATALOG_PAGES);
    }
    return (T)
        new PageResponse<>(
            walked.items(),
            0,
            walked.items().size(),
            walked.totalElements(),
            walked.items().isEmpty() ? 0 : 1,
            List.of());
  }

  /**
   * Evicts the named cache of each given {@link CacheDomain}; call after an admin mutation with the
   * domain(s) it changed so only the affected catalogues are dropped (FE-CACHE-2). A domain cache
   * absent from the manager is tolerated.
   *
   * @param domains the invalidation domains to clear
   */
  public void evict(CacheDomain... domains) {
    for (CacheDomain domain : domains) {
      Cache cache = cacheManager.getCache(domain.getCacheName());
      if (cache != null) {
        cache.clear();
      }
    }
    if (log.isDebugEnabled()) {
      log.debug("Evicted catalogue cache(s) {}", java.util.Arrays.toString(domains));
    }
  }

  /**
   * Evicts every catalogue domain — the coarse fallback for a genuinely multi-domain admin action
   * where the precise domain set is not known at the call site.
   */
  public void evictAllCatalogues() {
    evict(CacheDomain.values());
  }

  /**
   * Coarse evict-all fallback (drops every catalogue domain). Retained for admin mutations whose
   * changed domain set is not cleanly known at the call site (e.g. the shared {@code
   * AdminMissionDataPageController.okOrRelay} AJAX helper that spans job-types / squadrons /
   * frequency-types) — over-eviction is always safe, whereas a wrong narrow evict would strand
   * stale data (REQ-DATA-007). Prefer {@link #evict(CacheDomain...)} with the precise domain(s) at
   * any single-purpose site.
   */
  public void clearStaticDataCache() {
    evictAllCatalogues();
  }

  private <T> T executeGet(
      WebClient client, String uri, ParameterizedTypeReference<T> responseType) {
    try {
      return client.get().uri(uri).retrieve().bodyToMono(responseType).block();
    } catch (WebClientResponseException e) {
      return handleWebClientException(e, "GET", uri);
    } catch (Exception e) {
      return handleException(e, "GET", uri);
    }
  }

  private <T> T executeGet(
      WebClient client,
      String uriTemplate,
      ParameterizedTypeReference<T> responseType,
      Object... uriVariables) {
    try {
      return client
          .get()
          .uri(uriTemplate, uriVariables)
          .retrieve()
          .bodyToMono(responseType)
          .block();
    } catch (WebClientResponseException e) {
      return handleWebClientException(e, "GET", uriTemplate);
    } catch (Exception e) {
      return handleException(e, "GET", uriTemplate);
    }
  }

  private <T> T executeGet(WebClient client, String uri, Class<T> responseType) {
    try {
      return client.get().uri(uri).retrieve().bodyToMono(responseType).block();
    } catch (WebClientResponseException e) {
      return handleWebClientException(e, "GET", uri);
    } catch (Exception e) {
      return handleException(e, "GET", uri);
    }
  }

  /**
   * POST against the authenticated backend; {@code body} may be {@code null} for empty payloads.
   */
  public <T, R> R post(String uri, T body, Class<R> responseType) {
    return executePost(webClient, uri, body, responseType);
  }

  private <T, R> R executePost(WebClient client, String uri, T body, Class<R> responseType) {
    try {
      WebClient.RequestBodySpec spec = client.post().uri(uri);
      WebClient.RequestHeadersSpec<?> headersSpec = (body != null) ? spec.bodyValue(body) : spec;
      return headersSpec.retrieve().bodyToMono(responseType).block();
    } catch (WebClientResponseException e) {
      return handleWebClientException(e, "POST", uri);
    } catch (Exception e) {
      return handleException(e, "POST", uri);
    }
  }

  /** PUT against the authenticated backend; {@code body} may be {@code null} for empty payloads. */
  public <T, R> R put(String uri, T body, Class<R> responseType) {
    return executePut(webClient, uri, body, responseType);
  }

  private <T, R> R executePut(WebClient client, String uri, T body, Class<R> responseType) {
    try {
      WebClient.RequestBodySpec spec = client.put().uri(uri);
      WebClient.RequestHeadersSpec<?> headersSpec = (body != null) ? spec.bodyValue(body) : spec;
      return headersSpec.retrieve().bodyToMono(responseType).block();
    } catch (WebClientResponseException e) {
      return handleWebClientException(e, "PUT", uri);
    } catch (Exception e) {
      return handleException(e, "PUT", uri);
    }
  }

  /** DELETE against the authenticated backend; pass {@code Void.class} for 204 responses. */
  public <R> R delete(String uri, Class<R> responseType) {
    return executeDelete(webClient, uri, responseType);
  }

  /**
   * DELETE against the authenticated backend that carries a request body — needed by the inventory
   * per-allocation remove endpoint, which identifies the slice to drop through a body (dimension +
   * target + echoed version) rather than the URI. The body-less {@link #delete(String, Class)}
   * stays the default for the common no-payload case.
   *
   * @param uri the backend path.
   * @param body the request payload; must not be {@code null} (use {@link #delete(String, Class)}
   *     for a body-less DELETE).
   * @param responseType the expected response type.
   * @param <T> the request-body type.
   * @param <R> the response type.
   * @return the deserialized response body.
   */
  public <T, R> R delete(String uri, T body, Class<R> responseType) {
    try {
      return webClient
          .method(HttpMethod.DELETE)
          .uri(uri)
          .bodyValue(body)
          .retrieve()
          .bodyToMono(responseType)
          .block();
    } catch (WebClientResponseException e) {
      return handleWebClientException(e, "DELETE", uri);
    } catch (Exception e) {
      return handleException(e, "DELETE", uri);
    }
  }

  private <R> R executeDelete(WebClient client, String uri, Class<R> responseType) {
    try {
      return client.delete().uri(uri).retrieve().bodyToMono(responseType).block();
    } catch (WebClientResponseException e) {
      return handleWebClientException(e, "DELETE", uri);
    } catch (Exception e) {
      return handleException(e, "DELETE", uri);
    }
  }

  /**
   * PATCH against the authenticated backend; {@code body} may be {@code null} for empty payloads.
   */
  public <T, R> R patch(String uri, T body, Class<R> responseType) {
    return executePatch(webClient, uri, body, responseType);
  }

  private <T, R> R executePatch(WebClient client, String uri, T body, Class<R> responseType) {
    try {
      WebClient.RequestBodySpec spec = client.patch().uri(uri);
      WebClient.RequestHeadersSpec<?> headersSpec = (body != null) ? spec.bodyValue(body) : spec;
      return headersSpec.retrieve().bodyToMono(responseType).block();
    } catch (WebClientResponseException e) {
      return handleWebClientException(e, "PATCH", uri);
    } catch (Exception e) {
      return handleException(e, "PATCH", uri);
    }
  }

  private <T> T handleWebClientException(WebClientResponseException e, String method, String uri) {
    BackendServiceException parsed = BackendServiceException.fromProblem(e, objectMapper);
    // Log every RFC7807 backend failure exactly once, at the boundary, so individual page
    // controllers don't have to repeat the same boilerplate. Field errors and the
    // user-facing detail are included to make a 400 VALIDATION_FAILED diagnosable from the
    // log alone (see AGENTS.md / CHANGELOG); rejected user values are never logged.
    if (parsed.getStatusCode() >= 500) {
      log.error(
          "Backend error on {} {}: status={}, code={}, correlationId={}, detail={}, fieldErrors={}",
          method,
          uri,
          parsed.getStatusCode(),
          parsed.getProblemCode(),
          parsed.getCorrelationId(),
          parsed.getProblemDetail(),
          parsed.getFieldErrors());
    } else if (isExpectedAccessGateRefusal(parsed.getProblemCode())) {
      // Expected, high-frequency 403s, not faults. A pending-approval session polls several
      // endpoints on every page load; an unconsented session 403s on every request, because
      // BackendRoleSyncFilter's GET /api/v1/users/me is not exempt from the consent gate and its
      // failure path deliberately leaves the sync stamp unset so the next request retries. After a
      // terms change that is the whole squadron times every request. A role-less account is the
      // same shape at a smaller scale: every fragment of every page it loads, until an
      // administrator assigns a role. Log at DEBUG so none of them floods the client-error log
      // (mirrors the backend PendingApprovalAccessFilter and TermsAcceptanceAccessFilter, both of
      // which log their own refusal at DEBUG for the same reason). The backend-4xx metric below is
      // unaffected, so the monitoring signal stays.
      //
      // Reading the same predicate as the metric exclusion below is deliberate: the two lists were
      // written out separately and NO_ROLE was added to neither, so a third gate could diverge
      // from a fourth. One list, two readers.
      log.debug(
          "Backend client error on {} {}: status={}, code={}, correlationId={}",
          method,
          uri,
          parsed.getStatusCode(),
          parsed.getProblemCode(),
          parsed.getCorrelationId());
    } else {
      log.warn(
          "Backend client error on {} {}: status={}, code={}, correlationId={}, detail={},"
              + " fieldErrors={}",
          method,
          uri,
          parsed.getStatusCode(),
          parsed.getProblemCode(),
          parsed.getCorrelationId(),
          parsed.getProblemDetail(),
          parsed.getFieldErrors());
    }
    // The two access-gate refusals are NOT backend-call failures and must not be counted as such.
    // They are the application telling the user to do something — get approved, accept the terms —
    // and they arrive at the rate of "every unconsented session, every request". Counting them here
    // made `BackendCallFailureSustained` (sum(rate(basetool_backend_client_errors_total[5m])) >
    // 0.5)
    // fire 38 minutes after the consent gate shipped, at 3.2/s: the alert cannot tell "the backend
    // is failing" from "the gate is working", and during a rollout the second drowns the first.
    //
    // Nothing is lost. The backend counts each refusal itself, by code, in
    // basetool_http_error_total{code=PENDING_APPROVAL|TERMS_NOT_ACCEPTED}, and the consent rollout
    // has its own signal in TermsConsentRolloutStalled. Excluding only these two named codes keeps
    // the alert sensitive to every genuine 4xx storm — which dropping the whole backend_4xx bucket
    // from the alert expression would not.
    if (!isExpectedAccessGateRefusal(parsed.getProblemCode())) {
      countBackendError(
          parsed.getStatusCode() >= 500
              ? MetricNames.REASON_BACKEND_5XX
              : MetricNames.REASON_BACKEND_4XX,
          method);
    }
    throw parsed;
  }

  private <T> T handleException(Exception e, String method, String uri) {
    if (ReauthenticationRequiredException.isReauthSignal(e)) {
      // The frontend OAuth2 client has no usable token for this session (access token expired and
      // the refresh token was rejected / rotated away). This is a per-session auth state, not a
      // backend health problem — log it tersely (no stack trace, DEBUG) and rethrow a typed
      // exception so GlobalExceptionHandler can bounce the user through a fresh Keycloak login
      // instead of rendering an empty page and flooding the log with stack traces (REQ-SEC-012).
      log.debug(
          "Re-authentication required on {} {} (correlationId={})",
          method,
          uri,
          MDC.get("correlationId"));
      throw new ReauthenticationRequiredException(
          "Re-authentication required for " + method + " " + uri, e);
    }
    Throwable root = unwrap(e);
    if (root instanceof CallNotPermittedException) {
      // DEBUG, not WARN: the breaker's one-time OPEN transition already logged WARN
      // (ResilienceEventLogger). This branch fires for every call blocked while the breaker stays
      // open, so at WARN a routine backend restart floods the log (issue #1203, REQ-OBS-001). The
      // failure is still metered under reason=circuit_open below, so the count is never lost.
      log.debug("Circuit breaker open for {} {}: {}", method, uri, root.getMessage());
      countBackendError(MetricNames.REASON_CIRCUIT_OPEN, method);
      throw new BackendServiceException(
          "Backend circuit breaker open",
          e,
          503,
          BackendServiceException.CODE_SERVICE_UNAVAILABLE,
          null,
          java.util.Collections.emptyList(),
          null);
    }
    if (root instanceof BulkheadFullException) {
      log.warn("Bulkhead saturated for {} {}: {}", method, uri, root.getMessage());
      countBackendError(MetricNames.REASON_BULKHEAD_FULL, method);
      throw new BackendServiceException(
          "Backend bulkhead full",
          e,
          503,
          BackendServiceException.CODE_SERVICE_UNAVAILABLE,
          null,
          java.util.Collections.emptyList(),
          null);
    }
    if (root instanceof TimeoutException
        || root instanceof WebClientRequestException
        || root instanceof java.net.ConnectException) {
      log.warn("Backend timeout / connection failure on {} {}: {}", method, uri, root.getMessage());
      countBackendError(MetricNames.REASON_TIMEOUT, method);
      throw new BackendServiceException(
          "Backend timeout",
          e,
          504,
          BackendServiceException.CODE_BACKEND_TIMEOUT,
          null,
          java.util.Collections.emptyList(),
          null);
    }
    log.error("Unexpected backend error on {} {}: {}", method, uri, e.getMessage(), e);
    countBackendError(MetricNames.REASON_UNKNOWN, method);
    throw new BackendServiceException("Error on " + method + " data from backend", e, 500);
  }

  private static Throwable unwrap(Throwable t) {
    Throwable current = t;
    while (current != null) {
      if (current instanceof CallNotPermittedException
          || current instanceof BulkheadFullException
          || current instanceof TimeoutException
          || current instanceof WebClientRequestException
          || current instanceof java.net.ConnectException) {
        return current;
      }
      if (current.getCause() == current || current.getCause() == null) {
        return t;
      }
      current = current.getCause();
    }
    return t;
  }
}

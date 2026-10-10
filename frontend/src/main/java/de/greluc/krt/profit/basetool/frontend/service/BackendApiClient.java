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
import de.greluc.krt.profit.basetool.frontend.identity.model.TermsDocumentDto;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.support.CatalogPages;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * WebClient wrapper for the backend REST API: exposes typed overloads for every HTTP verb, each
 * with a URI-template twin, and maps every failure through {@link BackendErrorMapper} into {@link
 * BackendServiceException} or {@link ReauthenticationRequiredException}. {@code
 * getCached(CachedCatalog, ...)} adds Spring Cache, routing each {@link CachedCatalog} to its
 * domain cache via {@link CatalogCacheResolver}.
 *
 * <p>Resilience comes solely from the {@link
 * de.greluc.krt.profit.basetool.frontend.config.WebClientConfig#resilienceFilter WebClient filter
 * chain}: bulkhead, time limiter, retry (idempotent verbs only) and circuit breaker, for every
 * verb.
 *
 * <p>Page controllers let {@link
 * de.greluc.krt.profit.basetool.frontend.exception.GlobalExceptionHandler} surface failures rather
 * than catching {@link BackendServiceException}.
 */
@Service
@Slf4j
public class BackendApiClient {

  /** The path the wording lives at; the one URI {@link #termsDocumentClient} ever sees. */
  private static final String TERMS_DOCUMENT_URI = "/api/v1/terms/document";

  /** The filtered, resilient, bearer-relaying backend client. */
  private final WebClient webClient;

  /**
   * The bearer-less client for the Terms-of-Use wording, and nothing else (REQ-SEC-052); {@code
   * TermsDocumentClientUsageTest} refuses any other injection of it.
   */
  private final WebClient termsDocumentClient;

  /** Holds the catalogue caches {@link #evict(CacheDomain...)} clears. */
  private final CacheManager cacheManager;

  /** Maps every failed call; writes to this class's logger. */
  private final BackendErrorMapper errorMapper;

  /**
   * Creates the client.
   *
   * @param webClient the filtered, resilient, bearer-relaying backend client
   * @param termsDocumentClient the bearer-less client for the Terms-of-Use wording
   * @param meterRegistry receives {@code basetool_backend_client_errors_total}
   * @param cacheManager holds the catalogue caches
   */
  public BackendApiClient(
      WebClient webClient,
      WebClient termsDocumentClient,
      MeterRegistry meterRegistry,
      CacheManager cacheManager) {
    this.webClient = webClient;
    this.termsDocumentClient = termsDocumentClient;
    this.cacheManager = cacheManager;
    this.errorMapper = new BackendErrorMapper(meterRegistry, log);
  }

  /**
   * Reads the Terms-of-Use wording in force without a bearer token, for the public {@code /terms}
   * page (REQ-SEC-052).
   *
   * <p>The only anonymous backend call the frontend makes.
   *
   * @return the wording in force, or {@code null} when the backend returned no body
   */
  public TermsDocumentDto getTermsDocumentAnonymously() {
    return executeGet(termsDocumentClient, TERMS_DOCUMENT_URI, TermsDocumentDto.class);
  }

  /** GET against the authenticated backend, decoded via a {@link ParameterizedTypeReference}. */
  public <T> T get(String uri, ParameterizedTypeReference<T> responseType) {
    return executeGet(webClient, uri, responseType);
  }

  /**
   * GET against the authenticated backend, expanding {@code uriVariables} into {@code uriTemplate}
   * so the WebClient encodes them per RFC 3986. Prefer this over hand-encoding values with spaces
   * or reserved characters into the URI.
   *
   * @param uriTemplate the URI template containing {@code {name}} placeholders
   * @param responseType the decoded response type
   * @param uriVariables the values expanded into the template, encoded by the WebClient
   * @param <T> the response body type
   * @return the decoded response body
   */
  public <T> T get(
      String uriTemplate, ParameterizedTypeReference<T> responseType, Object... uriVariables) {
    return exchange(
        HttpMethod.GET,
        uriTemplate,
        () -> webClient.get().uri(uriTemplate, uriVariables),
        spec -> spec.bodyToMono(responseType));
  }

  /** GET overload for simple (non-generic) return types. */
  public <T> T get(String uri, Class<T> responseType) {
    return executeGet(webClient, uri, responseType);
  }

  /**
   * GET against the authenticated backend for a simple (non-generic) return type, expanding {@code
   * uriVariables} into {@code uriTemplate} so the WebClient encodes each value (REQ-SEC-051).
   *
   * @param uriTemplate the URI template containing {@code {name}} placeholders
   * @param responseType the decoded response class
   * @param uriVariables the values expanded into the template, in order
   * @param <T> the response body type
   * @return the decoded response body, or {@code null} when the backend returned none
   */
  public <T> T get(String uriTemplate, Class<T> responseType, Object... uriVariables) {
    return exchange(
        HttpMethod.GET,
        uriTemplate,
        () -> webClient.get().uri(uriTemplate, uriVariables),
        spec -> spec.bodyToMono(responseType));
  }

  /**
   * Cached GET of a {@link CachedCatalog}, stored in its domain cache (resolved by {@link
   * CatalogCacheResolver}) until the domain's TTL expires or an eviction. A {@link
   * CachedCatalog.Fetch#PAGE_WALK} catalogue is cached complete, every page merged (REQ-ADMIN-003).
   *
   * @param catalog the allowlisted catalogue to fetch and cache
   * @param responseType the decoded response type
   * @param <T> the response body type
   * @return the decoded (possibly cached) response body
   */
  @Cacheable(cacheResolver = "catalogCacheResolver", key = "#catalog.name()", sync = true)
  public <T> T getCached(CachedCatalog catalog, ParameterizedTypeReference<T> responseType) {
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
   * through {@link CatalogPages#fetchAll} and merging the contents into one {@link PageResponse},
   * which the caller's {@code @Cacheable} frame caches (REQ-ADMIN-003). Hitting {@link
   * CatalogPages#MAX_CATALOG_PAGES} logs a warning.
   *
   * @param catalog the page-walked catalogue to assemble
   * @param responseType the caller's declared response type, always {@code PageResponse<E>}
   * @param <T> the caller's response body type
   * @return the merged catalogue as a single {@code PageResponse}
   */
  @NotNull
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
      log.debug("Evicted catalogue cache(s) {}", Arrays.toString(domains));
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
   * Evicts every catalogue domain, for admin mutations whose affected domains are not known at the
   * call site (REQ-DATA-007). Prefer {@link #evict(CacheDomain...)} wherever the domain is known.
   */
  public void clearStaticDataCache() {
    evictAllCatalogues();
  }

  /**
   * GET against {@code client}, decoded via a {@link ParameterizedTypeReference}; the shared body
   * of the authenticated {@code get}/{@code getCached} overloads and the page walk.
   *
   * @param client the WebClient to send through (the authenticated one, or the bearer-less terms
   *     client)
   * @param uri the backend path, sent as-is
   * @param responseType the decoded response type
   * @param <T> the response body type
   * @return the decoded response body, or {@code null} when the backend returned none
   */
  private <T> T executeGet(
      WebClient client, String uri, ParameterizedTypeReference<T> responseType) {
    return exchange(
        HttpMethod.GET, uri, () -> client.get().uri(uri), spec -> spec.bodyToMono(responseType));
  }

  /**
   * Class-typed twin of {@link #executeGet(WebClient, String, ParameterizedTypeReference)}.
   *
   * @param client the WebClient to send through
   * @param uri the backend path, sent as-is
   * @param responseType the decoded response class
   * @param <T> the response body type
   * @return the decoded response body, or {@code null} when the backend returned none
   */
  private <T> T executeGet(WebClient client, String uri, Class<T> responseType) {
    return exchange(
        HttpMethod.GET, uri, () -> client.get().uri(uri), spec -> spec.bodyToMono(responseType));
  }

  /**
   * POST against the authenticated backend.
   *
   * @param uri the backend path, sent as-is
   * @param body the payload, or {@code null} for none
   * @param responseType the decoded response class
   * @param <T> the request-body type
   * @param <R> the response body type
   * @return the decoded response body, or {@code null} when the backend returned none
   */
  public <T, R> R post(String uri, T body, Class<R> responseType) {
    return exchange(
        HttpMethod.POST,
        uri,
        () -> withOptionalBody(webClient.post().uri(uri), body),
        spec -> spec.bodyToMono(responseType));
  }

  /**
   * POST against the authenticated backend, expanding {@code uriVariables} into {@code uriTemplate}
   * so the WebClient encodes each value (REQ-SEC-051).
   *
   * @param uriTemplate the URI template containing {@code {name}} placeholders
   * @param body the payload, or {@code null} for none
   * @param responseType the decoded response class
   * @param uriVariables the values expanded into the template, in order
   * @param <T> the request-body type
   * @param <R> the response body type
   * @return the decoded response body, or {@code null} when the backend returned none
   */
  public <T, R> R post(String uriTemplate, T body, Class<R> responseType, Object... uriVariables) {
    return exchange(
        HttpMethod.POST,
        uriTemplate,
        () -> withOptionalBody(webClient.post().uri(uriTemplate, uriVariables), body),
        spec -> spec.bodyToMono(responseType));
  }

  /**
   * PUT against the authenticated backend.
   *
   * @param uri the backend path, sent as-is
   * @param body the payload, or {@code null} for none
   * @param responseType the decoded response class
   * @param <T> the request-body type
   * @param <R> the response body type
   * @return the decoded response body, or {@code null} when the backend returned none
   */
  public <T, R> R put(String uri, T body, Class<R> responseType) {
    return exchange(
        HttpMethod.PUT,
        uri,
        () -> withOptionalBody(webClient.put().uri(uri), body),
        spec -> spec.bodyToMono(responseType));
  }

  /**
   * PUT against the authenticated backend, expanding {@code uriVariables} into {@code uriTemplate}
   * so the WebClient encodes each value (REQ-SEC-051).
   *
   * @param uriTemplate the URI template containing {@code {name}} placeholders
   * @param body the payload, or {@code null} for none
   * @param responseType the decoded response class
   * @param uriVariables the values expanded into the template, in order
   * @param <T> the request-body type
   * @param <R> the response body type
   * @return the decoded response body, or {@code null} when the backend returned none
   */
  public <T, R> R put(String uriTemplate, T body, Class<R> responseType, Object... uriVariables) {
    return exchange(
        HttpMethod.PUT,
        uriTemplate,
        () -> withOptionalBody(webClient.put().uri(uriTemplate, uriVariables), body),
        spec -> spec.bodyToMono(responseType));
  }

  /**
   * DELETE against the authenticated backend.
   *
   * @param uri the backend path, sent as-is
   * @param responseType the decoded response class; {@code Void.class} for a {@code 204}
   * @param <R> the response body type
   * @return the decoded response body, or {@code null} when the backend returned none
   */
  public <R> R delete(String uri, Class<R> responseType) {
    return exchange(
        HttpMethod.DELETE,
        uri,
        () -> webClient.delete().uri(uri),
        spec -> spec.bodyToMono(responseType));
  }

  /**
   * DELETE against the authenticated backend, expanding {@code uriVariables} into {@code
   * uriTemplate} so the WebClient encodes each value (REQ-SEC-051).
   *
   * @param uriTemplate the URI template containing {@code {name}} placeholders
   * @param responseType the decoded response class; {@code Void.class} for a {@code 204}
   * @param uriVariables the values expanded into the template, in order
   * @param <R> the response body type
   * @return the decoded response body, or {@code null} when the backend returned none
   */
  public <R> R delete(String uriTemplate, Class<R> responseType, Object... uriVariables) {
    return exchange(
        HttpMethod.DELETE,
        uriTemplate,
        () -> webClient.delete().uri(uriTemplate, uriVariables),
        spec -> spec.bodyToMono(responseType));
  }

  /**
   * DELETE against the authenticated backend that carries a request body, for an endpoint that
   * identifies the slice to drop through the body rather than the URI.
   *
   * @param uri the backend path, sent as-is
   * @param body the request payload; must not be {@code null}
   * @param responseType the decoded response class
   * @param <T> the request-body type
   * @param <R> the response body type
   * @return the decoded response body, or {@code null} when the backend returned none
   */
  public <T, R> R delete(String uri, T body, Class<R> responseType) {
    return exchange(
        HttpMethod.DELETE,
        uri,
        () -> webClient.method(HttpMethod.DELETE).uri(uri).bodyValue(body),
        spec -> spec.bodyToMono(responseType));
  }

  /**
   * DELETE with a request body, expanding {@code uriVariables} into {@code uriTemplate} so the
   * WebClient encodes each value (REQ-SEC-051).
   *
   * @param uriTemplate the URI template containing {@code {name}} placeholders
   * @param body the request payload; must not be {@code null}
   * @param responseType the decoded response class
   * @param uriVariables the values expanded into the template, in order
   * @param <T> the request-body type
   * @param <R> the response body type
   * @return the decoded response body, or {@code null} when the backend returned none
   */
  public <T, R> R delete(
      String uriTemplate, T body, Class<R> responseType, Object... uriVariables) {
    return exchange(
        HttpMethod.DELETE,
        uriTemplate,
        () -> webClient.method(HttpMethod.DELETE).uri(uriTemplate, uriVariables).bodyValue(body),
        spec -> spec.bodyToMono(responseType));
  }

  /**
   * PATCH against the authenticated backend.
   *
   * @param uri the backend path, sent as-is
   * @param body the payload, or {@code null} for none
   * @param responseType the decoded response class
   * @param <T> the request-body type
   * @param <R> the response body type
   * @return the decoded response body, or {@code null} when the backend returned none
   */
  public <T, R> R patch(String uri, T body, Class<R> responseType) {
    return exchange(
        HttpMethod.PATCH,
        uri,
        () -> withOptionalBody(webClient.patch().uri(uri), body),
        spec -> spec.bodyToMono(responseType));
  }

  /**
   * PATCH against the authenticated backend, expanding {@code uriVariables} into {@code
   * uriTemplate} so the WebClient encodes each value (REQ-SEC-051).
   *
   * @param uriTemplate the URI template containing {@code {name}} placeholders
   * @param body the payload, or {@code null} for none
   * @param responseType the decoded response class
   * @param uriVariables the values expanded into the template, in order
   * @param <T> the request-body type
   * @param <R> the response body type
   * @return the decoded response body, or {@code null} when the backend returned none
   */
  public <T, R> R patch(String uriTemplate, T body, Class<R> responseType, Object... uriVariables) {
    return exchange(
        HttpMethod.PATCH,
        uriTemplate,
        () -> withOptionalBody(webClient.patch().uri(uriTemplate, uriVariables), body),
        spec -> spec.bodyToMono(responseType));
  }

  /**
   * The single backend exchange every verb goes through: builds the request, retrieves, decodes and
   * blocks, mapping every failure the same way.
   *
   * <p>Every failure, including a Resilience4j refusal and a malformed URI template, goes through
   * {@link BackendErrorMapper#map}, so the call returns the body or throws {@link
   * BackendServiceException} or {@link ReauthenticationRequiredException}.
   *
   * @param method the HTTP verb, used only as the log field and the {@code method} metric label
   * @param uri the path or URI template, used only in log lines and exception messages
   * @param request builds the request up to (not including) {@code retrieve()}
   * @param decode turns the {@code retrieve()} spec into the body {@link Mono}
   * @param <R> the response body type
   * @return the decoded response body, or {@code null} when the backend returned none
   */
  private <R> R exchange(
      @NotNull HttpMethod method,
      @NotNull String uri,
      @NotNull Supplier<WebClient.RequestHeadersSpec<?>> request,
      @NotNull Function<WebClient.ResponseSpec, Mono<R>> decode) {
    try {
      return decode.apply(request.get().retrieve()).block();
    } catch (Exception e) {
      throw errorMapper.map(e, method.name(), uri);
    }
  }

  /**
   * Runs a backend call whose shape the typed verbs do not cover — a multipart upload, a binary or
   * bodiless response, a collected {@code Flux}, a per-request timeout — through the authenticated
   * client and the same error mapping, logging and {@code basetool_backend_client_errors_total}
   * accounting as every other call.
   *
   * <p>The caller builds the request on the supplied authenticated {@link WebClient} and chooses
   * how the response is decoded; every failure surfaces as {@link BackendServiceException} or
   * {@link ReauthenticationRequiredException}, never as a raw {@code WebClientResponseException}.
   *
   * @param method the HTTP verb, used only as the log field and the {@code method} metric label
   * @param uri the backend path, used only in log lines and exception messages
   * @param request builds the request on the authenticated client, up to (not including) {@code
   *     retrieve()}
   * @param decode turns the {@code retrieve()} spec into the body {@link Mono}
   * @param <R> the decoded body type
   * @return the decoded body, or {@code null} when the backend returned none
   */
  public <R> R execute(
      @NotNull HttpMethod method,
      @NotNull String uri,
      @NotNull Function<WebClient, WebClient.RequestHeadersSpec<?>> request,
      @NotNull Function<WebClient.ResponseSpec, Mono<R>> decode) {
    return exchange(method, uri, () -> request.apply(webClient), decode);
  }

  /**
   * Attaches {@code body} to a write request, or leaves the request body-less when it is {@code
   * null} — the POST/PUT/PATCH "empty payload" convention.
   *
   * @param spec the request after {@code uri(...)}
   * @param body the payload, or {@code null} for none
   * @return the request ready for {@code retrieve()}
   */
  @NotNull
  private static WebClient.RequestHeadersSpec<?> withOptionalBody(
      @NotNull WebClient.RequestBodySpec spec, @Nullable Object body) {
    return body != null ? spec.bodyValue(body) : spec;
  }
}

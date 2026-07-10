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

package de.greluc.krt.profit.basetool.backend.integration.scwiki;

import de.greluc.krt.profit.basetool.backend.config.ScWikiProperties;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiResponseDto;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only HTTP client for the SC Wiki catalogue API ({@code https://api.star-citizen.wiki}).
 *
 * <p>Mirrors {@link de.greluc.krt.profit.basetool.backend.integration.UexClient} for shared bits
 * (per-call timeout, fail-soft empty-list returns, ETag conditional GET, 16 MB in-memory buffer)
 * and adds three behaviours specific to the Wiki API (per SC_WIKI_SYNC_PLAN.md §5.3):
 *
 * <ol>
 *   <li><b>Pagination</b> — Wiki endpoints return a {@code {data, meta, links}} envelope where
 *       {@code meta.last_page} drives the page-walk loop. {@link #fetchAllPages} walks {@code
 *       ?page[number]=1..last_page} and merges every page's {@code data[]} into a single list.
 *   <li><b>Rate-limit pacing</b> — between page fetches inside one call, {@link
 *       #paceForRateLimit()} sleeps {@code 1000 / requestsPerSecond} milliseconds. The Wiki's
 *       advertised limit is 60 req/min on search and 10 on image-search; the conservative 5 req/s
 *       default leaves head-room for a future tightening. Tests subclass and override this hook to
 *       skip the sleep.
 *   <li><b>{@code include=} parameter</b> — appended to the query string when the caller passes a
 *       non-blank value. Wiki sub-resource fetches in R3+ use this for the {@code blueprints,items}
 *       eager-load pattern documented in plan §3.3.
 * </ol>
 *
 * <p>R1 ships the client itself; the actual {@code ScWikiCommoditySyncService} / {@code
 * ScWikiBlueprintSyncService} / {@code ScWikiItemSyncService} callers land in R3+. R1's test
 * coverage exercises the three pagination / ETag / rate-limit behaviours directly via {@code
 * MockWebServer}; the {@link #fetchAllPages} API is the only public surface today.
 *
 * <p>The ETag cache is keyed by the page-1 request URI (endpoint + include + page=1) so the
 * conditional-GET short-circuit fires as soon as the first page is unchanged. We deliberately do
 * NOT cache per page-N: a partial 200/304 mix across pages would produce a merged list with unknown
 * gaps. The simpler "page 1 304 ⇒ skip the whole run" matches the existing UEX semantics (an
 * unchanged feed → empty list → sync services treat as skip).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScWikiClient {

  /**
   * Per-call timeout for the underlying reactive request. Matches the UEX client (30 s) so a single
   * hung Wiki page does not delay the whole {@code ScWikiScheduler} tick beyond the scheduler's own
   * grace window.
   */
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);

  private final WebClient.Builder webClientBuilder;
  private final ScWikiProperties properties;

  /**
   * Micrometer registry for the swallowed-fetch-error counter ({@link
   * MetricNames#EXTERNAL_FETCH_ERRORS}). Injected so a network or parse failure — which the fetch
   * helpers below map to {@code null} / empty — still leaves a metric trail (REQ-OBS-011).
   */
  private final MeterRegistry meterRegistry;

  /** Reusable WebClient bound to the Wiki base URL. Built once after dependency injection. */
  private WebClient client;

  /**
   * Last-seen {@code ETag} response header value, keyed by the canonical page-1 request URI
   * (endpoint + include + page-1 params). Populated from the response of every successful 2xx fetch
   * of page 1 and replayed as {@code If-None-Match} on the next first-page fetch for the same
   * {@code (endpoint, include)} combination. {@link ConcurrentHashMap} because two scheduled SK
   * Wiki sync runs may overlap if the previous one is long-running.
   */
  private final Map<String, String> etagByFirstPageUri = new ConcurrentHashMap<>();

  /**
   * Jackson mapper used by {@link #fetchOne} to unwrap the optional single-resource {@code {data:
   * …}} envelope and bind the payload. A default Jackson 3 mapper is sufficient: the SC Wiki DTOs
   * use only core types (UUID / String / Double / Boolean / Map / nested records), so no extra
   * modules are needed. Declared {@code final} with an initializer so Lombok keeps it off the
   * generated constructor — existing unit tests that build the client directly stay
   * source-compatible.
   */
  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  /**
   * Builds the {@link WebClient} after dependency injection. Done once in {@code @PostConstruct}
   * instead of lazily per call so the Reactor-Netty connection pool from {@link
   * de.greluc.krt.profit.basetool.backend.config.WebClientConfig} is reused for every Wiki request
   * over the application's lifetime. The 16 MB in-memory codec ceiling matches {@code UexClient}:
   * the largest probed Wiki list page sits around 1.3 MB, the OpenAPI document around 700 KB.
   */
  @PostConstruct
  void initClient() {
    this.client =
        webClientBuilder
            .baseUrl(properties.getApiUrl())
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .build();
  }

  /**
   * Increments {@link MetricNames#EXTERNAL_FETCH_ERRORS} for the {@code scwiki} source. Called from
   * each branch that swallows an upstream network or parse failure into a {@code null} / empty
   * result, so a sustained Wiki outage is visible even though the sync services treat the empty
   * payload as a skip and the scheduled job still records a success (REQ-OBS-011).
   */
  private void recordFetchError() {
    meterRegistry
        .counter(
            MetricNames.EXTERNAL_FETCH_ERRORS, MetricNames.TAG_SOURCE, MetricNames.SOURCE_SCWIKI)
        .increment();
  }

  /**
   * Convenience overload of {@link #fetchAllPages(String, ParameterizedTypeReference, String,
   * String)} without the {@code include=} eager-load parameter. Used by R3 commodity / blueprint
   * list calls that don't need cross-resource eager loading.
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param endpoint Wiki endpoint path (e.g. {@code "/api/commodities"})
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel human-readable label for log lines (singular / plural to taste)
   * @return merged list of rows across all pages, or an empty list on 304 / error
   */
  public <T> List<T> fetchAllPages(
      String endpoint,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel) {
    return fetchAllPages(endpoint, typeRef, resourceLabel, null);
  }

  /**
   * Convenience overload of {@link #fetchAllPages(String, ParameterizedTypeReference, String,
   * String, Map)} with no query filters. R3 commodity / blueprint / vehicle list calls use this —
   * they never need a {@code filter[...]} parameter.
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param endpoint Wiki endpoint path (e.g. {@code "/api/commodities"})
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel human-readable label for log lines (singular / plural to taste)
   * @param include optional value for the {@code ?include=…} parameter (e.g. {@code
   *     "blueprints,items"}); {@code null} or blank means no include
   * @return merged list of rows across all pages, or an empty list on 304 / error
   */
  public <T> List<T> fetchAllPages(
      String endpoint,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel,
      String include) {
    return fetchAllPages(endpoint, typeRef, resourceLabel, include, null);
  }

  /**
   * Walks every page of a paginated Wiki endpoint and returns the concatenated {@code data[]}
   * across all pages. Behaviour:
   *
   * <ol>
   *   <li>Send page 1 with {@code If-None-Match} if we cached the ETag from a previous run. A
   *       {@code 304 Not Modified} short-circuits the entire call and returns an empty list — sync
   *       services treat that as "skip this run".
   *   <li>On a 2xx response, store the new ETag (if any) for the next call, accumulate {@code
   *       data[]} into the running list, and read {@code meta.last_page} to learn the page count.
   *   <li>For pages 2..N, sleep via {@link #paceForRateLimit()} between requests, fetch without an
   *       {@code If-None-Match} (fresh content this run), and append their {@code data[]}.
   *   <li>Any error / 5xx / timeout returns whatever has been accumulated so far (which may be an
   *       empty list if the failure was on page 1). The caller sees an empty list on total failure,
   *       matching the {@code UexClient} contract.
   * </ol>
   *
   * <p>Each non-blank {@code filters} entry is emitted as a {@code &filter[<key>]=<value>} query
   * parameter — the R5 Mode-B item backfill passes {@code filter[classification]=…} per kind
   * endpoint (SC_WIKI_SYNC_PLAN.md §3.4 / §8.4). The ETag cache key is the full first-page URI, so
   * differently-filtered passes over the same endpoint keep independent conditional-GET state. Pass
   * an order-preserving map ({@link java.util.LinkedHashMap} / {@link Map#of}) when supplying more
   * than one filter so the generated URI stays stable across runs.
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param endpoint Wiki endpoint path (e.g. {@code "/api/armor"})
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel human-readable label for log lines (singular / plural to taste)
   * @param include optional value for the {@code ?include=…} parameter (e.g. {@code
   *     "blueprints,items"}); {@code null} or blank means no include
   * @param filters optional {@code filter[<key>]=<value>} pairs; {@code null} / empty means none,
   *     and any entry with a blank value is skipped
   * @return merged list of rows across all pages, or an empty list on 304 / error
   */
  public <T> List<T> fetchAllPages(
      String endpoint,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel,
      String include,
      Map<String, String> filters) {
    log.info("Fetching all {} from SC Wiki API (paginated)", resourceLabel);

    String firstPageUri = buildPagedUri(endpoint, 1, include, filters);
    String previousEtag = etagByFirstPageUri.get(firstPageUri);

    ScWikiResponseDto<T> first =
        fetchSinglePage(firstPageUri, typeRef, resourceLabel, previousEtag);
    if (first == null) {
      return Collections.emptyList();
    }

    List<T> accumulated = new ArrayList<>();
    if (first.data() != null) {
      accumulated.addAll(first.data());
    }
    int lastPage =
        Optional.ofNullable(first.meta())
            .map(meta -> meta.lastPage() == null ? 1 : meta.lastPage())
            .orElse(1);

    for (int page = 2; page <= lastPage; page++) {
      paceForRateLimit();
      String pageUri = buildPagedUri(endpoint, page, include, filters);
      ScWikiResponseDto<T> next = fetchSinglePage(pageUri, typeRef, resourceLabel, null);
      if (next == null) {
        log.warn(
            "Page {} of {} failed mid-pagination; returning partial result of {} row(s).",
            page,
            resourceLabel,
            accumulated.size());
        break;
      }
      if (next.data() != null) {
        accumulated.addAll(next.data());
      }
    }

    log.info(
        "Fetched {} {} from SC Wiki API across {} page(s).",
        accumulated.size(),
        resourceLabel,
        Math.max(lastPage, 1));
    return accumulated;
  }

  /**
   * Fetches a single Wiki resource (e.g. {@code GET /api/items/{uuid}}) and binds it to {@code
   * type}. Used by the R4 closure-mode item sync, which resolves items one UUID at a time rather
   * than walking a list.
   *
   * <p>Envelope-tolerant: the Wiki wraps some single-resource responses in {@code {"data": {…}}}
   * and returns others flat. The method reads the body as a tree, unwraps a top-level {@code data}
   * node when present, then binds. A {@code 404} (the item is not on the Wiki) and any error /
   * timeout resolve to {@code null} — the caller treats {@code null} as "Wiki doesn't know this
   * one" and logs a {@code WIKI_MISSING} event. No ETag caching here (per-UUID fetches are one-shot
   * within a closure run).
   *
   * @param <T> the target DTO type
   * @param uri the relative request URI (e.g. {@code "/api/items/" + uuid})
   * @param type the DTO class to bind the payload to
   * @param resourceLabel human-readable label for log lines
   * @return the parsed DTO, or {@code null} on 404 / error / unparseable body
   */
  public <T> T fetchOne(String uri, Class<T> type, String resourceLabel) {
    log.debug("Fetching one {} from SC Wiki API: {}", resourceLabel, uri);
    // Decode to a raw String, then parse + unwrap with this client's own mapper. The Wiki wraps
    // some
    // single-resource responses in {"data": {…}} and returns others flat, so reading the body as a
    // tree and unwrapping a top-level "data" node before binding is simpler and more robust than a
    // codec-level bind that would have to know about the envelope.
    String rawBody =
        client
            .get()
            .uri(uri)
            .exchangeToMono(
                response -> {
                  int status = response.statusCode().value();
                  if (status == 404 || status == 304) {
                    return Mono.<String>empty();
                  }
                  if (!response.statusCode().is2xxSuccessful()) {
                    return response.createError();
                  }
                  return response.bodyToMono(String.class);
                })
            .timeout(CALL_TIMEOUT)
            .onErrorResume(
                e -> {
                  log.warn("Failed to fetch {} from SC Wiki API ({})", resourceLabel, uri, e);
                  recordFetchError();
                  return Mono.empty();
                })
            .blockOptional()
            .orElse(null);
    if (rawBody == null || rawBody.isBlank()) {
      return null;
    }
    try {
      JsonNode body = objectMapper.readTree(rawBody);
      JsonNode payload = body.has("data") ? body.get("data") : body;
      return objectMapper.treeToValue(payload, type);
    } catch (Exception e) {
      log.warn("Failed to parse {} response from SC Wiki API ({})", resourceLabel, uri, e);
      recordFetchError();
      return null;
    }
  }

  /**
   * Issues a single GET against the given prebuilt URI, honouring the optional {@code
   * If-None-Match} header. Returns the parsed envelope on 2xx, {@code null} on 304 / error.
   *
   * <p>On 2xx, the response ETag (if any) is stored against the same URI so the next call can
   * replay it as {@code If-None-Match}. ETag storage is keyed by the URI, NOT by the page number
   * alone, so the same endpoint with different {@code include=} values keeps independent cache
   * entries.
   *
   * @param <T> per-row payload type
   * @param requestUri prebuilt request URI (including page / include / version params)
   * @param typeRef typed envelope reference
   * @param resourceLabel log label
   * @param previousEtag optional value for {@code If-None-Match}; {@code null} to skip
   * @return parsed envelope, or {@code null} on 304 / error
   */
  private <T> ScWikiResponseDto<T> fetchSinglePage(
      String requestUri,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel,
      String previousEtag) {
    // .uri(String) parses as a URI template, prepends the configured baseUrl when the URI is
    // relative, and treats already-encoded sequences (%5B / %5D) as literal — exactly what
    // buildPagedUri produces. Passing a URI directly would BYPASS the baseUrl (Spring treats a
    // URI argument as fully resolved), which caused MockWebServer tests to hit localhost:80.
    WebClient.RequestHeadersSpec<?> request = client.get().uri(requestUri);
    if (previousEtag != null && !previousEtag.isBlank()) {
      request = request.header(HttpHeaders.IF_NONE_MATCH, previousEtag);
    }
    return request
        .exchangeToMono(
            response -> {
              int status = response.statusCode().value();
              if (status == 304) {
                log.debug(
                    "SC Wiki {} page unchanged since last sync (304 Not Modified) — skipping.",
                    resourceLabel);
                return Mono.<ScWikiResponseDto<T>>empty();
              }
              if (!response.statusCode().is2xxSuccessful()) {
                return response.createError();
              }
              String etag = response.headers().asHttpHeaders().getETag();
              if (etag != null && !etag.isBlank()) {
                etagByFirstPageUri.put(requestUri, etag);
              }
              return response.bodyToMono(typeRef);
            })
        .timeout(CALL_TIMEOUT)
        .onErrorResume(
            e -> {
              log.warn("Failed to fetch {} from SC Wiki API ({})", resourceLabel, requestUri, e);
              recordFetchError();
              return Mono.<ScWikiResponseDto<T>>empty();
            })
        .blockOptional()
        .orElse(null);
  }

  /**
   * Builds the page URI as a relative path with unencoded brackets. WebClient's default URI builder
   * encodes {@code [} / {@code ]} to {@code %5B} / {@code %5D} on the wire — a previous attempt
   * that pre-encoded the brackets caused double-encoding ({@code %255B}) because Spring's default
   * mode treats already-encoded sequences in a non-template URI literal as content to be encoded
   * again. The wiki accepts both encoded and unencoded brackets in keys; producing the unencoded
   * form keeps the WebClient happy and the test assertions on the recorded request path see the
   * canonical encoded form.
   *
   * <p>Appended params (in order): {@code page[number]}, {@code page[size]}, {@code include} (if
   * non-blank), each {@code filter[<key>]} (for every non-blank entry, in the map's iteration
   * order), {@code version} (if non-blank). Commas inside {@code include} stay literal and are
   * encoded by the WebClient to {@code %2C}.
   *
   * @param endpoint Wiki endpoint path
   * @param pageNumber 1-based page index
   * @param include optional eager-load string, or {@code null} / blank
   * @param filters optional {@code filter[<key>]=<value>} pairs, or {@code null} / empty; entries
   *     with a blank key or value are skipped
   * @return relative URI string passed to the WebClient via {@code .uri(String)}
   */
  private String buildPagedUri(
      String endpoint, int pageNumber, String include, Map<String, String> filters) {
    StringBuilder sb = new StringBuilder(endpoint);
    sb.append("?page[number]=").append(pageNumber);
    sb.append("&page[size]=").append(properties.getPageSize());
    if (include != null && !include.isBlank()) {
      sb.append("&include=").append(include);
    }
    if (filters != null) {
      for (Map.Entry<String, String> entry : filters.entrySet()) {
        String key = entry.getKey();
        String value = entry.getValue();
        if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
          sb.append("&filter[").append(key).append("]=").append(value);
        }
      }
    }
    if (properties.getGameVersion() != null && !properties.getGameVersion().isBlank()) {
      sb.append("&version=").append(properties.getGameVersion());
    }
    return sb.toString();
  }

  /**
   * Sleeps {@code 1000 / requestsPerSecond} milliseconds between page fetches. Public so the
   * SC-Wiki sync orchestrators in the {@code service.scwiki} package can pace their own
   * multi-request loops between client calls (they live in a different package since the cycle
   * cleanup that left only the HTTP client in {@code integration.scwiki}), and so unit tests can
   * subclass and override it with a no-op to keep test latency bounded while still exercising the
   * pagination + ETag paths.
   *
   * <p>Interruption is preserved (re-sets the thread's interrupted flag) so a shutting-down
   * scheduler thread can exit promptly instead of being parked inside a long sleep.
   */
  public void paceForRateLimit() {
    int rps = properties.getRequestsPerSecond() == null ? 5 : properties.getRequestsPerSecond();
    long sleepMillis = Math.max(1L, 1000L / Math.max(1, rps));
    try {
      Thread.sleep(sleepMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}

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
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiMetaDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiResponseDto;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
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
   * List-returning convenience over {@link #fetchAllPagesResult(String, ParameterizedTypeReference,
   * String, String, Map)} — drops the {@code notModified} flag and returns just the merged rows
   * (empty on 304 / error). Retained so callers that do not need to tell an unchanged catalogue
   * from an empty one stay source-compatible.
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param endpoint Wiki endpoint path
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel human-readable label for log lines
   * @param include optional value for the {@code ?include=…} parameter; {@code null} / blank means
   *     no include
   * @param filters optional {@code filter[<key>]=<value>} pairs; {@code null} / empty means none
   * @return merged list of rows across all pages, or an empty list on 304 / error
   */
  public <T> List<T> fetchAllPages(
      String endpoint,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel,
      String include,
      Map<String, String> filters) {
    return fetchAllPagesResult(endpoint, typeRef, resourceLabel, include, filters).data();
  }

  /**
   * Walks every page of a paginated Wiki endpoint and returns the concatenated {@code data[]}
   * across all pages, together with whether page 1 answered {@code 304 Not Modified}. Behaviour:
   *
   * <ol>
   *   <li>Send page 1 with {@code If-None-Match} if we cached the ETag from a previous run. A
   *       {@code 304 Not Modified} short-circuits the entire call and returns {@link
   *       FetchResult#notModified()} (empty data, {@code notModified == true}) — sync services
   *       treat that as "catalogue unchanged; skip the re-import but report the live row count".
   *   <li>On a 2xx response, store the new ETag (if any) for the next call, accumulate {@code
   *       data[]} into the running list, and read {@code meta.last_page} to learn the page count.
   *   <li>For pages 2..N, sleep via {@link #paceForRateLimit()} between requests, fetch without an
   *       {@code If-None-Match} (fresh content this run), and append their {@code data[]}.
   *   <li>Any error / 5xx / timeout returns whatever has been accumulated so far (which may be an
   *       empty list if the failure was on page 1) with {@code notModified == false} and {@code
   *       complete == false}. A genuine empty-200 also returns an empty list with {@code
   *       notModified == false} — matching the {@code UexClient} contract so a real outage still
   *       reports zero rows to the caller.
   *   <li>The walk additionally cross-checks the envelope's own pagination metadata and flags the
   *       result {@link FetchResult#complete() incomplete} when it cannot vouch for the census:
   *       {@code meta.last_page} absent while page 1 came back full (the signature of an upstream
   *       field rename, which {@code @JsonIgnoreProperties(ignoreUnknown = true)} turns into a
   *       silent {@code null} instead of a parse error), a page failing mid-walk, or {@code
   *       meta.total} disagreeing with the merged row count. Each of those also increments {@link
   *       MetricNames#EXTERNAL_FETCH_ERRORS} so a contract break is visible in metrics, not just in
   *       the log.
   * </ol>
   *
   * <p><b>Why {@code complete} exists (H5):</b> the merged list feeds tombstone sweeps that mark
   * every catalogue row NOT in it {@code scwiki_deleted}. A half-walked feed still returns hundreds
   * of perfectly real rows, so the pre-existing "did we see anything at all?" gate waves it through
   * and the un-fetched remainder gets soft-deleted. The flag is the caller's only way to tell "the
   * Wiki no longer lists these" from "we never asked".
   *
   * <p><b>Why the flag matters (#1182):</b> a 304 and a genuine empty-200 both yield an empty list,
   * so a caller that only sees the list cannot tell an <em>unchanged</em> (healthy) catalogue from
   * an <em>outage</em>. Because the {@code scwiki_sync} step counts upserts, an all-304 run would
   * report {@code 0} items — indistinguishable from an empty-catalogue outage — and false-fire the
   * {@code SyncZeroItems} alert once uptime exceeds its window. The {@code notModified} flag lets
   * the step report its live row count on a fully-cached run instead, so only a genuine empty-200
   * reads as zero. Mirrors the {@code UexClient.FetchResult} carve-out added for {@code uex_sync}.
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
   * @return the merged rows across all pages plus the page-1 {@code notModified} flag and the
   *     {@code complete} census flag; the data is empty both on a 304 (flag {@code true}) and on a
   *     genuine empty-200 / error (flag {@code false})
   */
  public <T> FetchResult<T> fetchAllPagesResult(
      String endpoint,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel,
      String include,
      Map<String, String> filters) {
    log.info("Fetching all {} from SC Wiki API (paginated)", resourceLabel);

    String firstPageUri = buildPagedUri(endpoint, 1, include, filters);
    String previousEtag = etagByFirstPageUri.get(firstPageUri);

    PageOutcome<T> firstOutcome =
        fetchSinglePage(firstPageUri, typeRef, resourceLabel, previousEtag);
    if (firstOutcome.notModified()) {
      // Page-1 304: the catalogue is byte-identical to the last successful fetch. Surface it as a
      // distinct outcome (empty data + notModified=true) so the caller reports its live row count
      // rather than 0 — an unchanged catalogue must not read as a zero-item outage (#1182).
      return FetchResult.unchanged();
    }
    ScWikiResponseDto<T> first = firstOutcome.body();
    if (first == null) {
      // Genuine empty-200 / network / parse failure (NOT a 304) — an empty list with the flag
      // cleared so a real outage still surfaces as zero items to SyncZeroItems. The walk never
      // enumerated the catalogue, so it is reported as INCOMPLETE and no caller may tombstone.
      return FetchResult.partial(Collections.emptyList());
    }

    List<T> accumulated = new ArrayList<>();
    if (first.data() != null) {
      accumulated.addAll(first.data());
    }

    ScWikiMetaDto meta = first.meta();
    boolean complete = true;
    int lastPage = 1;
    if (meta == null || meta.lastPage() == null) {
      // The envelope carries no page count. Two very different situations share this shape, and
      // only one of them is healthy: a genuinely single-page result (page 1 came back short), and
      // an upstream contract break — a renamed/moved meta field that @JsonIgnoreProperties turns
      // into a silent null rather than an exception. The tell is a FULL page 1: the Wiki filled the
      // page size exactly, so there is almost certainly a page 2 we would never ask for, and every
      // row on it would then read as "no longer in the Wiki feed" to an orphan sweep.
      if (isFullPage(accumulated.size())) {
        log.warn(
            "SC Wiki {} returned no pagination metadata (meta.last_page absent) while page 1 came"
                + " back full at {} row(s) — treating the page walk as INCOMPLETE; later pages were"
                + " never requested and must not be mistaken for deleted rows.",
            resourceLabel,
            accumulated.size());
        recordFetchError();
        complete = false;
      }
    } else {
      lastPage = Math.max(1, meta.lastPage());
    }

    int pagesFetched = 1;
    for (int page = 2; page <= lastPage; page++) {
      paceForRateLimit();
      String pageUri = buildPagedUri(endpoint, page, include, filters);
      ScWikiResponseDto<T> next = fetchSinglePage(pageUri, typeRef, resourceLabel, null).body();
      if (next == null) {
        // fetchSinglePage already logged the cause and counted the fetch error.
        log.warn(
            "Page {} of {} failed mid-pagination; returning an INCOMPLETE result of {} row(s).",
            page,
            resourceLabel,
            accumulated.size());
        complete = false;
        break;
      }
      pagesFetched++;
      if (next.data() != null) {
        accumulated.addAll(next.data());
      }
    }

    if (meta != null && meta.total() != null && meta.total() != accumulated.size()) {
      // meta.total is the upstream's own row count for this filter. A mismatch means the walk
      // merged fewer (or more) rows than the feed claims to hold — a dropped page, a page-size
      // disagreement, or a catalogue that changed mid-walk. Whatever the cause, the accumulated
      // list is not a faithful census, so it must not drive a tombstone sweep.
      log.warn(
          "SC Wiki {} page walk merged {} row(s) but meta.total reports {} — treating the result as"
              + " INCOMPLETE; the accumulated rows are not a full census of the feed.",
          resourceLabel,
          accumulated.size(),
          meta.total());
      recordFetchError();
      complete = false;
    }

    log.info(
        "Fetched {} {} from SC Wiki API across {} of {} announced page(s) (complete={}).",
        accumulated.size(),
        resourceLabel,
        pagesFetched,
        lastPage,
        complete);
    return complete ? FetchResult.of(accumulated) : FetchResult.partial(accumulated);
  }

  /**
   * Convenience overload of {@link #fetchAllPagesResult(String, ParameterizedTypeReference, String,
   * String, Map)} with no {@code include} and no {@code filter[...]} params. Used by the commodity
   * / vehicle / blueprint / manufacturer list syncs, which need the {@code notModified} flag to
   * distinguish an unchanged catalogue (report the live row count) from a genuine empty response
   * (report 0) for the {@code SyncZeroItems} alert (#1182).
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param endpoint Wiki endpoint path (e.g. {@code "/api/commodities"})
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel human-readable label for log lines
   * @return the merged rows plus whether page 1 answered 304 Not Modified
   */
  public <T> FetchResult<T> fetchAllPagesResult(
      String endpoint,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel) {
    return fetchAllPagesResult(endpoint, typeRef, resourceLabel, null, null);
  }

  /**
   * Reports whether a page's row count fills the configured {@code page[size]} exactly, which is
   * what makes "there is probably another page" the likelier reading of missing pagination
   * metadata. A non-positive / absent configured page size makes this unanswerable, so it answers
   * {@code false} — an unknown page size must not manufacture a warning.
   *
   * @param rowCount the number of rows page 1 carried
   * @return {@code true} when the configured page size is known, positive and fully used up
   */
  private boolean isFullPage(int rowCount) {
    Integer pageSize = properties.getPageSize();
    return pageSize != null && pageSize > 0 && rowCount >= pageSize;
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
   * @return the page outcome: a parsed envelope on 2xx, a {@link PageOutcome#notModified()} marker
   *     on 304, or a null-bodied {@link PageOutcome#error()} on empty / error
   */
  private <T> PageOutcome<T> fetchSinglePage(
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
                return Mono.just(PageOutcome.<T>unchanged());
              }
              if (!response.statusCode().is2xxSuccessful()) {
                return response.createError();
              }
              String etag = response.headers().asHttpHeaders().getETag();
              if (etag != null && !etag.isBlank()) {
                etagByFirstPageUri.put(requestUri, etag);
              }
              return response.bodyToMono(typeRef).map(PageOutcome::ok);
            })
        .timeout(CALL_TIMEOUT)
        .onErrorResume(
            e -> {
              log.warn("Failed to fetch {} from SC Wiki API ({})", resourceLabel, requestUri, e);
              recordFetchError();
              return Mono.just(PageOutcome.<T>error());
            })
        .blockOptional()
        .orElse(PageOutcome.error());
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

  /**
   * Outcome of a paginated Wiki fetch: the merged rows plus whether the upstream answered {@code
   * 304 Not Modified} on page 1. A 304 short-circuits the whole call (the class contract: "page 1
   * 304 ⇒ skip the whole run"), so {@link #data()} is empty in that case — but {@link
   * #notModified()} lets a caller tell an <em>unchanged</em> catalogue (healthy, nothing to
   * re-import) apart from a genuine empty-200 / error, which also yields an empty list. The {@code
   * scwiki_sync} step services use the flag to report their live row count instead of {@code 0} on
   * a fully-cached run so a healthy stable catalogue does not read as a zero-item outage to {@code
   * SyncZeroItems} (#1182 — mirrors the {@code UexClient.FetchResult} carve-out for {@code
   * uex_sync}).
   *
   * <p>{@link #complete()} is the separate, stricter question: did the page walk actually enumerate
   * the whole feed? It is {@code false} whenever a page failed mid-walk, page 1 itself failed, the
   * pagination metadata went missing on a full first page, or {@code meta.total} disagreed with the
   * merged row count. <b>Only a {@code complete} result may drive a tombstone sweep</b> — rows that
   * were never fetched are indistinguishable from rows the Wiki dropped, and an ungated sweep would
   * mark the whole un-fetched remainder {@code scwiki_deleted}. {@code ScWikiOrphanSweep} enforces
   * this; the syncs with an inline sweep check the flag themselves.
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param data the merged rows across all pages; empty on 304 / error
   * @param notModified {@code true} iff page 1 answered 304 Not Modified
   * @param complete {@code true} iff the page walk enumerated the whole feed and the merged row
   *     count agreed with the upstream's own {@code meta.total}
   */
  public record FetchResult<T>(List<T> data, boolean notModified, boolean complete) {

    /**
     * Wraps a fully-walked list as a <em>modified, complete</em> result — the healthy 2xx outcome,
     * including a genuine empty-200 whose envelope was intact.
     *
     * @param <T> per-row payload type
     * @param data the merged rows (possibly empty)
     * @return a result carrying {@code data} with {@code notModified == false, complete == true}
     */
    public static <T> FetchResult<T> of(List<T> data) {
      return new FetchResult<>(data, false, true);
    }

    /**
     * Wraps a list the page walk could not finish (failed page, missing pagination metadata on a
     * full page, or a {@code meta.total} mismatch) as an <em>incomplete</em> result. The rows are
     * still returned — they are valid, they are just not the whole feed — so upserts proceed while
     * every tombstone sweep stands down.
     *
     * @param <T> per-row payload type
     * @param data the rows merged before the walk was abandoned (possibly empty)
     * @return a result carrying {@code data} with {@code notModified == false, complete == false}
     */
    public static <T> FetchResult<T> partial(List<T> data) {
      return new FetchResult<>(data, false, false);
    }

    /**
     * The {@code 304 Not Modified} result: empty data with {@code notModified == true}. Named
     * {@code unchanged} (not {@code notModified}) so the factory does not clash with the record's
     * generated {@link #notModified()} accessor. {@code complete} is {@code false}: a conditional
     * GET enumerates nothing, so a caller that ignored {@link #notModified()} still cannot sweep.
     *
     * @param <T> per-row payload type
     * @return a not-modified result with an empty data list
     */
    public static <T> FetchResult<T> unchanged() {
      return new FetchResult<>(List.of(), true, false);
    }
  }

  /**
   * Internal outcome of a single page fetch: the parsed envelope (or {@code null}) plus whether the
   * upstream answered {@code 304 Not Modified}. Distinguishing a 304 from a {@code null} body
   * (empty / error) at the page level is what lets {@link #fetchAllPagesResult} carry the {@code
   * notModified} signal up to the sync services without conflating an unchanged catalogue with an
   * outage.
   *
   * @param <T> per-row payload type
   * @param body the parsed page envelope, or {@code null} on 304 / empty / error
   * @param notModified {@code true} iff the upstream answered 304 Not Modified
   */
  private record PageOutcome<T>(ScWikiResponseDto<T> body, boolean notModified) {

    /**
     * A successful 2xx page carrying a parsed envelope.
     *
     * @param <T> per-row payload type
     * @param body the parsed page envelope
     * @return an outcome wrapping {@code body} with {@code notModified == false}
     */
    private static <T> PageOutcome<T> ok(ScWikiResponseDto<T> body) {
      return new PageOutcome<>(body, false);
    }

    /**
     * A {@code 304 Not Modified} page: no body, {@code notModified == true}. Named {@code
     * unchanged} (not {@code notModified}) so the factory does not clash with the record's
     * generated {@link #notModified()} accessor.
     *
     * @param <T> per-row payload type
     * @return a not-modified outcome
     */
    private static <T> PageOutcome<T> unchanged() {
      return new PageOutcome<>(null, true);
    }

    /**
     * A failed / empty page: no body, {@code notModified == false}.
     *
     * @param <T> per-row payload type
     * @return an error outcome
     */
    private static <T> PageOutcome<T> error() {
      return new PageOutcome<>(null, false);
    }
  }
}

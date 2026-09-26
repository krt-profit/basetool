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

import de.greluc.krt.profit.basetool.backend.config.ResponseSizeLimitInterceptor;
import de.greluc.krt.profit.basetool.backend.config.ScWikiProperties;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiMetaDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiResponseDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiRow;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Read-only HTTP client for the SC Wiki catalogue API ({@code https://api.star-citizen.wiki}).
 *
 * <p>Walks paginated endpoints ({@link #fetchAllPages}), paces page fetches via {@link
 * #paceForRateLimit()}, sends ETag conditional GETs keyed by the page-1 URI (a page-1 304 skips the
 * whole walk) and caps response bodies at 16 MB. Failures are fail-soft and yield empty or partial
 * results.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScWikiClient {

  /**
   * Largest response body one Wiki call may deliver (16 MiB), the ceiling the reactive codec used
   * to enforce. See {@link #initClient()} for why it is a backstop and not a comfortable margin.
   */
  static final long MAX_RESPONSE_BYTES = 16L * 1024 * 1024;

  /**
   * A fresh, observed builder from {@code RestClientConfig} (prototype-scoped), so the base URL set
   * here does not leak into any other client. Its request factory bounds each call by the same 30 s
   * read timeout as the UEX client, so a single hung Wiki page cannot delay the whole {@code
   * ScWikiScheduler} tick beyond the scheduler's own grace window.
   */
  private final RestClient.Builder restClientBuilder;

  /** The {@code app.scwiki.*} configuration: base URL, page sizes, pacing and game version. */
  private final ScWikiProperties properties;

  /**
   * Micrometer registry for the swallowed-fetch-error counter ({@link
   * MetricNames#EXTERNAL_FETCH_ERRORS}). Injected so a network or parse failure — which the fetch
   * helpers below map to {@code null} / empty — still leaves a metric trail (REQ-OBS-011).
   */
  private final MeterRegistry meterRegistry;

  /** Reusable client bound to the Wiki base URL. Built once after dependency injection. */
  private RestClient client;

  /**
   * Last-seen {@code ETag} per canonical page-1 request URI, replayed as {@code If-None-Match} on
   * the next first-page fetch of the same URI.
   */
  private final Map<String, String> etagByFirstPageUri = new ConcurrentHashMap<>();

  /**
   * Jackson mapper that {@link #fetchOne} uses to unwrap the optional {@code {data: …}} envelope
   * and bind the payload.
   */
  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  /**
   * Builds the {@link RestClient} once after dependency injection, with the {@link
   * #MAX_RESPONSE_BYTES} response-body cap. A response over the cap fails the decode and yields an
   * empty result.
   */
  @PostConstruct
  void initClient() {
    this.client =
        restClientBuilder
            .baseUrl(properties.apiUrl())
            .requestInterceptor(new ResponseSizeLimitInterceptor(MAX_RESPONSE_BYTES))
            .build();
  }

  /**
   * Increments {@link MetricNames#EXTERNAL_FETCH_ERRORS} for the {@code scwiki} source at most once
   * per fetch (REQ-OBS-011).
   *
   * @param latch the current fetch's latch; only the first call through it records
   */
  private void recordFetchErrorOnce(FetchErrorLatch latch) {
    if (!latch.claim()) {
      return;
    }
    meterRegistry
        .counter(
            MetricNames.EXTERNAL_FETCH_ERRORS, MetricNames.TAG_SOURCE, MetricNames.SOURCE_SCWIKI)
        .increment();
  }

  /**
   * Overload of {@link #fetchAllPages(String, ParameterizedTypeReference, String, String)} without
   * an {@code include=} parameter.
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param endpoint Wiki endpoint path (e.g. {@code "/api/commodities"})
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel label for log lines
   * @return merged rows across all pages, or an empty list on 304 / error
   */
  public <T extends ScWikiRow> List<T> fetchAllPages(
      String endpoint,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel) {
    return fetchAllPages(endpoint, typeRef, resourceLabel, null);
  }

  /**
   * Overload of {@link #fetchAllPages(String, ParameterizedTypeReference, String, String, Map)}
   * without query filters.
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param endpoint Wiki endpoint path (e.g. {@code "/api/commodities"})
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel label for log lines
   * @param include optional {@code ?include=…} value; {@code null} or blank means none
   * @return merged rows across all pages, or an empty list on 304 / error
   */
  public <T extends ScWikiRow> List<T> fetchAllPages(
      String endpoint,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel,
      String include) {
    return fetchAllPages(endpoint, typeRef, resourceLabel, include, null);
  }

  /**
   * Returns only the merged rows of {@link #fetchAllPagesResult(String, ParameterizedTypeReference,
   * String, String, Map)}, dropping the {@code notModified} and {@code complete} flags.
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param endpoint Wiki endpoint path
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel label for log lines
   * @param include optional {@code ?include=…} value; {@code null} or blank means none
   * @param filters optional {@code filter[<key>]=<value>} pairs; {@code null} or empty means none
   * @return merged rows across all pages, or an empty list on 304 / error
   */
  public <T extends ScWikiRow> List<T> fetchAllPages(
      String endpoint,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel,
      String include,
      Map<String, String> filters) {
    return fetchAllPagesResult(endpoint, typeRef, resourceLabel, include, filters).data();
  }

  /**
   * Walks every page of a paginated Wiki endpoint and returns the merged {@code data[]} with a
   * page-1 {@code notModified} flag and a {@code complete} census flag.
   *
   * <p>Page 1 carries the cached ETag, and a 304 short-circuits to an empty not-modified result;
   * pages 2..N are paced by {@link #paceForRateLimit()}. An error returns the rows gathered so far.
   * The result is incomplete when a page fails, pagination metadata is missing on a full page 1, a
   * row repeats, the distinct rows fall short of {@code meta.total}, or the page count grows during
   * the walk; a surplus over {@code meta.total} is not a failure. Each non-blank filter is sent as
   * {@code filter[<key>]=<value>}.
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param endpoint Wiki endpoint path (e.g. {@code "/api/armor"})
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel label for log lines
   * @param include optional {@code ?include=…} value; {@code null} or blank means none
   * @param filters optional {@code filter[<key>]=<value>} pairs in a stable iteration order; {@code
   *     null} or empty means none, blank values are skipped
   * @return the merged rows plus the {@code notModified} and {@code complete} flags
   */
  public <T extends ScWikiRow> FetchResult<T> fetchAllPagesResult(
      String endpoint,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel,
      String include,
      Map<String, String> filters) {
    return fetchAllPagesResult(endpoint, typeRef, resourceLabel, include, filters, null);
  }

  /**
   * Variant of {@link #fetchAllPagesResult(String, ParameterizedTypeReference, String, String,
   * Map)} with a per-endpoint {@code page[size]} override for endpoints with heavy rows.
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param endpoint Wiki endpoint path
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel label for log lines
   * @param include optional {@code ?include=…} value; {@code null} or blank means none
   * @param filters optional {@code filter[<key>]=<value>} pairs; {@code null} or empty means none
   * @param pageSizeOverride rows per page, or {@code null} / non-positive for the configured
   *     default
   * @return the merged rows plus the {@code notModified} and {@code complete} flags
   */
  public <T extends ScWikiRow> FetchResult<T> fetchAllPagesResult(
      String endpoint,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel,
      String include,
      Map<String, String> filters,
      Integer pageSizeOverride) {
    log.info("Fetching all {} from SC Wiki API (paginated)", resourceLabel);

    int pageSize = effectivePageSize(pageSizeOverride);
    String firstPageUri = buildPagedUri(endpoint, 1, include, filters, pageSize);
    String previousEtag = etagByFirstPageUri.get(firstPageUri);
    FetchErrorLatch errorLatch = new FetchErrorLatch();

    PageOutcome<T> firstOutcome =
        fetchSinglePage(firstPageUri, typeRef, resourceLabel, previousEtag, errorLatch);
    if (firstOutcome.notModified()) {
      return FetchResult.unchanged();
    }
    ScWikiResponseDto<T> first = firstOutcome.body();
    if (first == null) {
      forgetFirstPageEtag(firstPageUri);
      return FetchResult.partial(Collections.emptyList());
    }

    List<T> accumulated = new ArrayList<>();
    if (first.data() != null) {
      accumulated.addAll(first.data());
    }

    ScWikiMetaDto meta = first.meta();
    ScWikiMetaDto freshestMeta = meta;
    boolean complete = true;
    boolean walkAbandoned = false;
    int lastPage = 1;
    if (meta == null || meta.lastPage() == null) {
      if (isFullPage(accumulated.size(), pageSize)) {
        log.warn(
            "SC Wiki {} returned no pagination metadata (meta.last_page absent) while page 1 came"
                + " back full at {} row(s) — treating the page walk as INCOMPLETE; later pages were"
                + " never requested and must not be mistaken for deleted rows.",
            resourceLabel,
            accumulated.size());
        recordFetchErrorOnce(errorLatch);
        complete = false;
      }
    } else {
      lastPage = Math.max(1, meta.lastPage());
    }

    int pagesFetched = 1;
    for (int page = 2; page <= lastPage; page++) {
      paceForRateLimit();
      String pageUri = buildPagedUri(endpoint, page, include, filters, pageSize);
      ScWikiResponseDto<T> next =
          fetchSinglePage(pageUri, typeRef, resourceLabel, null, errorLatch).body();
      if (next == null) {
        log.warn(
            "Page {} of {} failed mid-pagination; returning an INCOMPLETE result of {} row(s).",
            page,
            resourceLabel,
            accumulated.size());
        recordFetchErrorOnce(errorLatch);
        complete = false;
        walkAbandoned = true;
        break;
      }
      pagesFetched++;
      if (next.meta() != null) {
        freshestMeta = next.meta();
      }
      if (next.data() != null) {
        accumulated.addAll(next.data());
      }
    }

    int distinctRows = countDistinctRows(accumulated);
    int repeatedRows = accumulated.size() - distinctRows;
    if (repeatedRows > 0) {
      log.warn(
          "SC Wiki {} page walk merged {} row(s) but only {} distinct one(s) — the feed was"
              + " re-paginated mid-walk, so {} row(s) came back twice and an unknown number never"
              + " came back at all; treating the result as INCOMPLETE.",
          resourceLabel,
          accumulated.size(),
          distinctRows,
          repeatedRows);
      recordFetchErrorOnce(errorLatch);
      complete = false;
    }

    Integer announcedTotal = meta == null ? null : meta.total();
    if (announcedTotal != null && distinctRows < announcedTotal) {
      log.warn(
          "SC Wiki {} page walk enumerated {} distinct row(s) but meta.total reports {} — {} row(s)"
              + " are unaccounted for; treating the result as INCOMPLETE, the accumulated rows are"
              + " not a full census of the feed.",
          resourceLabel,
          distinctRows,
          announcedTotal,
          announcedTotal - distinctRows);
      recordFetchErrorOnce(errorLatch);
      complete = false;
    } else if (announcedTotal != null && distinctRows > announcedTotal) {
      log.info(
          "SC Wiki {} page walk enumerated {} distinct row(s) while meta.total reports {} — every"
              + " announced page was fetched and no row came back twice, so the surplus is an"
              + " upstream count under-reporting its own feed (or rows added mid-walk), not a gap;"
              + " the census stands.",
          resourceLabel,
          distinctRows,
          announcedTotal);
    }

    if (!walkAbandoned
        && freshestMeta != null
        && freshestMeta.lastPage() != null
        && freshestMeta.lastPage() > lastPage) {
      log.warn(
          "SC Wiki {} announced {} page(s) on page 1 but {} by the end of the walk — the {} page(s)"
              + " past the original bound were never requested; treating the result as INCOMPLETE.",
          resourceLabel,
          lastPage,
          freshestMeta.lastPage(),
          freshestMeta.lastPage() - lastPage);
      recordFetchErrorOnce(errorLatch);
      complete = false;
    }

    log.info(
        "Fetched {} {} ({} distinct) from SC Wiki API across {} of {} announced page(s)"
            + " (complete={}).",
        accumulated.size(),
        resourceLabel,
        distinctRows,
        pagesFetched,
        lastPage,
        complete);
    if (!complete) {
      forgetFirstPageEtag(firstPageUri);
    }
    return complete ? FetchResult.of(accumulated) : FetchResult.partial(accumulated);
  }

  /**
   * Overload of {@link #fetchAllPagesResult(String, ParameterizedTypeReference, String, String,
   * Map)} without {@code include} and without filters.
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param endpoint Wiki endpoint path (e.g. {@code "/api/commodities"})
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel label for log lines
   * @return the merged rows plus the {@code notModified} and {@code complete} flags
   */
  public <T extends ScWikiRow> FetchResult<T> fetchAllPagesResult(
      String endpoint,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel) {
    return fetchAllPagesResult(endpoint, typeRef, resourceLabel, null, null);
  }

  /**
   * Reports whether page 1 filled the requested page size, which makes missing pagination metadata
   * suspicious.
   *
   * @param rowCount the number of rows page 1 carried
   * @param pageSize the page size the walk requested
   * @return {@code true} when the page size is positive and fully used
   */
  private boolean isFullPage(int rowCount, int pageSize) {
    return pageSize > 0 && rowCount >= pageSize;
  }

  /**
   * Resolves the {@code page[size]} a walk requests: a positive override, else the configured
   * default, else 200.
   *
   * @param pageSizeOverride the per-call override, or {@code null}
   * @return the page size to request
   */
  private int effectivePageSize(Integer pageSizeOverride) {
    if (pageSizeOverride != null && pageSizeOverride > 0) {
      return pageSizeOverride;
    }
    Integer configured = properties.pageSize();
    return configured != null && configured > 0 ? configured : 200;
  }

  /**
   * Counts the distinct rows a page walk enumerated, keyed on {@link ScWikiRow#uuid()}; each row
   * without a UUID counts as its own row.
   *
   * @param <T> per-row payload type
   * @param rows the merged rows across every fetched page
   * @return the number of distinct UUIDs plus the number of rows without a UUID
   */
  private static <T extends ScWikiRow> int countDistinctRows(@NotNull List<T> rows) {
    Set<UUID> seenIds = HashSet.newHashSet(rows.size());
    int idlessRows = 0;
    for (T row : rows) {
      UUID id = row == null ? null : row.uuid();
      if (id == null) {
        idlessRows++;
      } else {
        seenIds.add(id);
      }
    }
    return seenIds.size() + idlessRows;
  }

  /**
   * Fetches a single Wiki resource and binds it to {@code type}, unwrapping a top-level {@code
   * data} envelope when present. No ETag caching.
   *
   * @param <T> the target DTO type
   * @param uri the relative request URI (e.g. {@code "/api/items/" + uuid})
   * @param type the DTO class to bind the payload to
   * @param resourceLabel label for log lines
   * @return the parsed DTO, or {@code null} on 404 / error / unparseable body
   */
  @Nullable
  public <T> T fetchOne(String uri, Class<T> type, String resourceLabel) {
    log.debug("Fetching one {} from SC Wiki API: {}", resourceLabel, uri);
    FetchErrorLatch errorLatch = new FetchErrorLatch();
    String rawBody;
    try {
      rawBody =
          client
              .get()
              .uri(uri)
              .exchange(
                  (clientRequest, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 404 || status == 304) {
                      return null;
                    }
                    if (!response.getStatusCode().is2xxSuccessful()) {
                      throw response.createException();
                    }
                    return response.bodyTo(String.class);
                  });
    } catch (RuntimeException e) {
      log.warn("Failed to fetch {} from SC Wiki API ({})", resourceLabel, uri, e);
      recordFetchErrorOnce(errorLatch);
      return null;
    }
    if (rawBody == null || rawBody.isBlank()) {
      return null;
    }
    try {
      JsonNode body = objectMapper.readTree(rawBody);
      JsonNode payload = body.has("data") ? body.get("data") : body;
      return objectMapper.treeToValue(payload, type);
    } catch (Exception e) {
      log.warn("Failed to parse {} response from SC Wiki API ({})", resourceLabel, uri, e);
      recordFetchErrorOnce(errorLatch);
      return null;
    }
  }

  /**
   * Issues one GET for a prebuilt URI, sending {@code If-None-Match} when an ETag is given, and
   * stores the response ETag under that URI on 2xx.
   *
   * @param <T> per-row payload type
   * @param requestUri prebuilt request URI including page, include and version params
   * @param typeRef typed envelope reference
   * @param resourceLabel log label
   * @param previousEtag value for {@code If-None-Match}, or {@code null} to skip
   * @param errorLatch the enclosing fetch's one-increment error latch
   * @return a parsed envelope on 2xx, {@link PageOutcome#notModified()} on 304, or a null-bodied
   *     {@link PageOutcome#error()} on empty / error
   */
  private <T> PageOutcome<T> fetchSinglePage(
      String requestUri,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel,
      String previousEtag,
      FetchErrorLatch errorLatch) {
    RestClient.RequestHeadersSpec<?> request = client.get().uri(requestUri);
    if (previousEtag != null && !previousEtag.isBlank()) {
      request = request.header(HttpHeaders.IF_NONE_MATCH, previousEtag);
    }
    try {
      return request.exchangeForRequiredValue(
          (clientRequest, response) -> {
            int status = response.getStatusCode().value();
            if (status == 304) {
              log.debug(
                  "SC Wiki {} page unchanged since last sync (304 Not Modified) — skipping.",
                  resourceLabel);
              return PageOutcome.<T>unchanged();
            }
            if (!response.getStatusCode().is2xxSuccessful()) {
              throw response.createException();
            }
            String etag = response.getHeaders().getETag();
            if (etag != null && !etag.isBlank()) {
              etagByFirstPageUri.put(requestUri, etag);
            }
            ScWikiResponseDto<T> body = response.bodyTo(typeRef);
            return body == null ? PageOutcome.<T>error() : PageOutcome.ok(body);
          });
    } catch (RuntimeException e) {
      log.warn("Failed to fetch {} from SC Wiki API ({})", resourceLabel, requestUri, e);
      recordFetchErrorOnce(errorLatch);
      return PageOutcome.error();
    }
  }

  /**
   * Builds the relative page URI with unencoded brackets, leaving encoding to the RestClient.
   *
   * <p>Params in order: {@code page[number]}, {@code page[size]}, {@code include} (if non-blank),
   * each non-blank {@code filter[<key>]} in map order, {@code version} (if non-blank).
   *
   * @param endpoint Wiki endpoint path
   * @param pageNumber 1-based page index
   * @param include optional eager-load string, or {@code null} / blank
   * @param filters optional {@code filter[<key>]=<value>} pairs, or {@code null} / empty; entries
   *     with a blank key or value are skipped
   * @param pageSize the {@code page[size]} resolved by {@link #effectivePageSize}
   * @return relative URI string for {@code .uri(String)}
   */
  private String buildPagedUri(
      String endpoint, int pageNumber, String include, Map<String, String> filters, int pageSize) {
    StringBuilder sb = new StringBuilder(endpoint);
    sb.append("?page[number]=").append(pageNumber);
    sb.append("&page[size]=").append(pageSize);
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
    if (properties.gameVersion() != null && !properties.gameVersion().isBlank()) {
      sb.append("&version=").append(properties.gameVersion());
    }
    return sb.toString();
  }

  /**
   * Sleeps {@code 1000 / requestsPerSecond} milliseconds (default 5 requests per second) between
   * page fetches. An interrupt re-sets the thread's interrupted flag and ends the sleep.
   */
  public void paceForRateLimit() {
    int rps = properties.requestsPerSecond() == null ? 5 : properties.requestsPerSecond();
    long sleepMillis = Math.max(1L, 1000L / Math.max(1, rps));
    try {
      Thread.sleep(sleepMillis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /**
   * Outcome of a paginated Wiki fetch: the merged rows, whether page 1 answered {@code 304 Not
   * Modified}, and whether the walk enumerated the whole feed.
   *
   * <p>Only a {@code complete} result may drive a tombstone sweep.
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param data the merged rows across all pages; empty on 304 / error
   * @param notModified {@code true} iff page 1 answered 304 Not Modified
   * @param complete {@code true} iff every announced page was fetched, no row was served twice, and
   *     no shortfall against {@code meta.total} occurred
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
    @NotNull
    public static <T> FetchResult<T> of(List<T> data) {
      return new FetchResult<>(data, false, true);
    }

    /**
     * Wraps the rows of a walk that could not enumerate the whole feed as an <em>incomplete</em>
     * result; upserts may use them, tombstone sweeps must not.
     *
     * @param <T> per-row payload type
     * @param data the rows merged before the walk was abandoned (possibly empty)
     * @return a result carrying {@code data} with {@code notModified == false, complete == false}
     */
    @NotNull
    public static <T> FetchResult<T> partial(List<T> data) {
      return new FetchResult<>(data, false, false);
    }

    /**
     * The {@code 304 Not Modified} result: empty data, {@code notModified == true} and {@code
     * complete == false}.
     *
     * @param <T> per-row payload type
     * @return a not-modified result with an empty data list
     */
    @NotNull
    public static <T> FetchResult<T> unchanged() {
      return new FetchResult<>(List.of(), true, false);
    }
  }

  /**
   * Outcome of a single page fetch: the parsed envelope or {@code null}, plus whether the upstream
   * answered {@code 304 Not Modified}.
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
    @NotNull
    private static <T> PageOutcome<T> ok(ScWikiResponseDto<T> body) {
      return new PageOutcome<>(body, false);
    }

    /**
     * A {@code 304 Not Modified} page: no body, {@code notModified == true}.
     *
     * @param <T> per-row payload type
     * @return a not-modified outcome
     */
    @NotNull
    private static <T> PageOutcome<T> unchanged() {
      return new PageOutcome<>(null, true);
    }

    /**
     * A failed / empty page: no body, {@code notModified == false}.
     *
     * @param <T> per-row payload type
     * @return an error outcome
     */
    @NotNull
    private static <T> PageOutcome<T> error() {
      return new PageOutcome<>(null, false);
    }
  }

  /**
   * One-shot claim for a single fetch's {@link MetricNames#EXTERNAL_FETCH_ERRORS} increment,
   * created per {@link #fetchAllPagesResult} walk or {@link #fetchOne} call. Not thread-safe;
   * confined to the thread driving its fetch.
   */
  private static final class FetchErrorLatch {

    /** Whether this fetch has already contributed its single counter increment. */
    private boolean recorded;

    /**
     * Claims this fetch's one increment for the caller.
     *
     * @return {@code true} on the first invocation for this latch — the caller must record —, and
     *     {@code false} on every subsequent one, meaning the increment is already spent
     */
    private boolean claim() {
      if (recorded) {
        return false;
      }
      recorded = true;
      return true;
    }
  }

  /**
   * Drops the cached page-1 {@code ETag} after a walk that produced no complete census, so the next
   * run refetches every page instead of receiving a 304.
   *
   * @param firstPageUri the canonical page-1 request URI of the walk; never null
   */
  private void forgetFirstPageEtag(String firstPageUri) {
    if (etagByFirstPageUri.remove(firstPageUri) != null) {
      log.debug(
          "Dropped the cached page-1 ETag after an incomplete walk so the next run re-fetches"
              + " unconditionally instead of being answered 304.");
    }
  }
}

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
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiRow;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
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
   * over the application's lifetime.
   *
   * <p>The 16 MB in-memory codec ceiling matches {@code UexClient}. It is <b>not</b> the
   * comfortable margin an earlier version of this comment claimed ("the largest probed Wiki list
   * page sits around 1.3 MB"): measured against the live API on 2026-08-28, {@code /api/vehicles}
   * at the default page size of 200 answers with <b>10.4 MB</b> on page 1, because a vehicle row
   * carries its entire port / shield / power tree. Exceeding the ceiling throws inside the decode,
   * which this client swallows into an empty list — so the sync would stop silently rather than
   * fail loudly. The vehicle walk therefore requests a smaller page (see {@code
   * ScWikiProperties.vehiclesPageSize}), which is the real fix; the ceiling is the backstop.
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
   * Increments {@link MetricNames#EXTERNAL_FETCH_ERRORS} for the {@code scwiki} source, but at most
   * once for the fetch {@code latch} belongs to. Called from each branch that swallows an upstream
   * network or parse failure — or a broken pagination contract — into a {@code null} / empty /
   * incomplete result, so a sustained Wiki outage is visible even though the sync services treat
   * the empty payload as a skip and the scheduled job still records a success (REQ-OBS-011).
   *
   * <p>The latch exists because one page walk can exhibit several independent problems at once: a
   * page failing mid-walk, absent pagination metadata on a full page 1, and a {@code meta.total}
   * disagreement are not mutually exclusive, and two pairings are in fact the norm — a partially
   * renamed {@code meta} block loses {@code last_page} while still stating a {@code total}, and a
   * page dropped mid-walk is itself what makes the merged rows fall short of it. Counting every
   * symptom would scale the external-error rate with the number of symptoms instead of the number
   * of failed fetches, so one broken feed would read as a two- or three-fold outage on the
   * dashboards. Every symptom still gets its own WARN line — an operator wants all of them; it is
   * only the counter that must stay one-per-fetch.
   *
   * @param latch the current fetch's latch; the first call through it records, every later call
   *     through the same instance is a no-op
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
  public <T extends ScWikiRow> List<T> fetchAllPages(
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
  public <T extends ScWikiRow> List<T> fetchAllPages(
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
  public <T extends ScWikiRow> List<T> fetchAllPages(
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
   *       silent {@code null} instead of a parse error), a page failing mid-walk, the same row
   *       coming back on two pages, the distinct rows falling <em>short</em> of {@code meta.total},
   *       or the feed announcing more pages by the end of the walk than page 1 did. Each of those
   *       warns on its own so an operator sees every symptom, and together they increment {@link
   *       MetricNames#EXTERNAL_FETCH_ERRORS} <b>at most once per call</b> (see {@link
   *       #recordFetchErrorOnce}) so a contract break is visible in metrics without one failed
   *       fetch inflating the error rate by its number of symptoms.
   *   <li>A <em>surplus</em> — more distinct rows than {@code meta.total} claims — is explicitly
   *       <b>not</b> a census failure. An upstream count query may under-report what its own
   *       paginator serves ({@code /api/items}: 12 331 distinct rows across 62 announced pages
   *       against a stated total of 12 283), and rows appended mid-walk look the same. Neither can
   *       hide a row from a tombstone sweep, whereas the duplicate and shortfall checks above catch
   *       every shape that can — so the surplus is reported at {@code INFO} and the census stands.
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
  public <T extends ScWikiRow> FetchResult<T> fetchAllPagesResult(
      String endpoint,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel,
      String include,
      Map<String, String> filters) {
    return fetchAllPagesResult(endpoint, typeRef, resourceLabel, include, filters, null);
  }

  /**
   * {@link #fetchAllPagesResult(String, ParameterizedTypeReference, String, String, Map)} with a
   * per-endpoint {@code page[size]} override.
   *
   * <p>Exists because one endpoint's rows can be far heavier than the shared page size assumes:
   * {@code /api/vehicles} serves each vehicle's whole port / shield / power tree, so page 1 at the
   * default 200 is <b>10.4 MB</b> against the 16 MB codec ceiling described in the class doc — and
   * crossing that ceiling does not truncate, it throws, is swallowed into an empty list, and stops
   * the sync silently. The override is a page size, not a byte budget, because that is the only
   * knob the upstream offers.
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param endpoint Wiki endpoint path
   * @param typeRef typed wrapper carrying the parametric envelope type
   * @param resourceLabel human-readable label for log lines
   * @param include optional value for the {@code ?include=…} parameter; {@code null} / blank means
   *     none
   * @param filters optional {@code filter[<key>]=<value>} pairs; {@code null} / empty means none
   * @param pageSizeOverride rows per page for this walk, or {@code null} to use the configured
   *     default; a non-positive value is ignored the same way
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
    // One latch for the whole walk: however many distinct problems this fetch turns out to have,
    // they collectively contribute exactly one external-fetch-error increment.
    FetchErrorLatch errorLatch = new FetchErrorLatch();

    PageOutcome<T> firstOutcome =
        fetchSinglePage(firstPageUri, typeRef, resourceLabel, previousEtag, errorLatch);
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
      forgetFirstPageEtag(firstPageUri);
      return FetchResult.partial(Collections.emptyList());
    }

    List<T> accumulated = new ArrayList<>();
    if (first.data() != null) {
      accumulated.addAll(first.data());
    }

    // Page 1's meta is the census BASELINE: what the feed claimed about itself before the walk
    // started. Later pages' meta is tracked separately (freshestMeta) so a feed that grows past its
    // own announced page count mid-walk is still caught — but the row-count baseline stays page
    // 1's on purpose. On a SHRINKING feed the fresher, lower total would hide exactly the rows a
    // mid-walk deletion pushed out of the pagination window before the walk reached them.
    ScWikiMetaDto meta = first.meta();
    ScWikiMetaDto freshestMeta = meta;
    boolean complete = true;
    boolean walkAbandoned = false;
    int lastPage = 1;
    if (meta == null || meta.lastPage() == null) {
      // The envelope carries no page count. Two very different situations share this shape, and
      // only one of them is healthy: a genuinely single-page result (page 1 came back short), and
      // an upstream contract break — a renamed/moved meta field that @JsonIgnoreProperties turns
      // into a silent null rather than an exception. The tell is a FULL page 1: the Wiki filled the
      // page size exactly, so there is almost certainly a page 2 we would never ask for, and every
      // row on it would then read as "no longer in the Wiki feed" to an orphan sweep.
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
        // fetchSinglePage already logged the transport cause; the latch keeps this walk's total at
        // one increment while still covering the bodiless-2xx case it does not count itself.
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
      // The walk was served the same row twice. A consistent snapshot never repeats a row, so this
      // is a pagination window that moved underneath the walk: an upstream insert or delete shifts
      // every later row across the page boundaries, re-serving some and pushing others out of view
      // entirely. The rows that were pushed out are precisely the ones a tombstone sweep would read
      // as deleted — and no row-COUNT comparison can see them, because a duplicate and an omission
      // cancel each other out in the total.
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
      // A SHORTFALL: the feed states more rows than the walk can account for — a dropped page, a
      // page-size disagreement, or rows deleted mid-walk shifting later ones out of the window.
      // Whichever it is, rows the Wiki still lists are missing from the merged list, so the list
      // must not drive a tombstone sweep.
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
      // A SURPLUS is not a gap, and is deliberately NOT a census failure. The upstream's own count
      // query can disagree with what its own paginator serves — /api/items answers 12 331 distinct
      // rows across its 62 announced pages for a stated total of 12 283 (reproduced against the
      // live API on 2026-08-28, stable across the whole walk, while every kind endpoint agrees
      // exactly) — and rows appended while the walk ran land here too. Neither can hide a row from
      // the sweep: the surplus rows were SEEN, and an omission would have surfaced above as a
      // duplicate or a shortfall. Reading a surplus as "incomplete" is what suppressed the
      // cross-kind orphan sweep on every single run.
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
      // The loop bound was fixed when page 1 answered, and by the end of the walk the feed
      // announced more pages than that. The tail was never requested — the same "we never asked"
      // case as a dropped page, and the reason a surplus above is allowed to stand: growth that
      // outruns the announced page count is caught here instead.
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
  public <T extends ScWikiRow> FetchResult<T> fetchAllPagesResult(
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
   * @param pageSize the page size this walk actually requested
   * @return {@code true} when the page size is positive and fully used up
   */
  private boolean isFullPage(int rowCount, int pageSize) {
    return pageSize > 0 && rowCount >= pageSize;
  }

  /**
   * Resolves the {@code page[size]} a walk should request: the caller's override when it is
   * positive, else the configured default, else 200.
   *
   * <p>A non-positive override is treated as absent rather than sent on the wire, so a
   * misconfiguration degrades to the default instead of asking the upstream for zero rows and
   * reading the empty answer as an outage.
   *
   * @param pageSizeOverride the per-call override, or {@code null}
   * @return the page size to request
   */
  private int effectivePageSize(Integer pageSizeOverride) {
    if (pageSizeOverride != null && pageSizeOverride > 0) {
      return pageSizeOverride;
    }
    Integer configured = properties.getPageSize();
    return configured != null && configured > 0 ? configured : 200;
  }

  /**
   * Counts how many <em>distinct</em> rows a page walk actually enumerated, keyed on {@link
   * ScWikiRow#uuid()}.
   *
   * <p>This is the census measure, and the merged row count is not: a walk over a feed that is
   * being written to re-serves rows it has already passed and skips others, so the two counts
   * diverge exactly when the merged list stops being a faithful enumeration. Comparing sizes alone
   * cannot see that — one duplicate and one omission cancel out — which is why the caller compares
   * this number, not {@code accumulated.size()}, against the upstream's stated total, and treats
   * any repetition at all as a census failure in its own right.
   *
   * <p>A row the upstream served without a UUID cannot be deduplicated, so each one counts as its
   * own row rather than collapsing every id-less row into a single "duplicate" — an endpoint that
   * omits UUIDs must not read as a feed that repeated itself hundreds of times.
   *
   * @param <T> per-row payload type
   * @param rows the merged rows across every fetched page
   * @return the number of distinct UUIDs plus the number of rows that carried no UUID
   */
  private static <T extends ScWikiRow> int countDistinctRows(List<T> rows) {
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
    // One latch for this single-resource fetch too: the transport and the parse branch below are
    // mutually exclusive today, but the latch makes "one fetch, at most one increment" structural
    // rather than a property of the current control flow.
    FetchErrorLatch errorLatch = new FetchErrorLatch();
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
                  recordFetchErrorOnce(errorLatch);
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
      recordFetchErrorOnce(errorLatch);
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
   * @param errorLatch the enclosing fetch's one-increment latch, so a transport failure here and a
   *     completeness problem the caller detects afterwards do not both count
   * @return the page outcome: a parsed envelope on 2xx, a {@link PageOutcome#notModified()} marker
   *     on 304, or a null-bodied {@link PageOutcome#error()} on empty / error
   */
  private <T> PageOutcome<T> fetchSinglePage(
      String requestUri,
      ParameterizedTypeReference<ScWikiResponseDto<T>> typeRef,
      String resourceLabel,
      String previousEtag,
      FetchErrorLatch errorLatch) {
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
              recordFetchErrorOnce(errorLatch);
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
   * @param pageSize the {@code page[size]} to request — already resolved by {@link
   *     #effectivePageSize}, so every page of one walk asks for the same size and the ETag cache
   *     key stays stable
   * @return relative URI string passed to the WebClient via {@code .uri(String)}
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
   * pagination metadata went missing on a full first page, a row came back on two pages, the
   * distinct rows fell short of {@code meta.total}, or the feed announced more pages by the end of
   * the walk than it did on page 1. <b>Only a {@code complete} result may drive a tombstone
   * sweep</b> — rows that were never fetched are indistinguishable from rows the Wiki dropped, and
   * an ungated sweep would mark the whole un-fetched remainder {@code scwiki_deleted}. {@code
   * ScWikiOrphanSweep} enforces this; the syncs with an inline sweep check the flag themselves.
   *
   * @param <T> per-row payload type inside {@link ScWikiResponseDto#data()}
   * @param data the merged rows across all pages; empty on 304 / error
   * @param notModified {@code true} iff page 1 answered 304 Not Modified
   * @param complete {@code true} iff the page walk enumerated the whole feed: every announced page
   *     fetched, no row served twice, and no shortfall against the upstream's own {@code
   *     meta.total}
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
     * full page, a repeated row, a shortfall against {@code meta.total}, or a page count that grew
     * past the walk's bound) as an <em>incomplete</em> result. The rows are still returned — they
     * are valid, they are just not the whole feed — so upserts proceed while every tombstone sweep
     * stands down.
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

  /**
   * One-shot claim ticket for a single fetch's {@link MetricNames#EXTERNAL_FETCH_ERRORS} increment.
   * One instance is created per {@link #fetchAllPagesResult} page walk (and per {@link #fetchOne}
   * call) and handed to every branch that may want to count an error, so the counter tracks the
   * number of failed <em>fetches</em> rather than the number of symptoms a failed fetch happened to
   * show. The counterpart WARN lines are deliberately NOT latched — each one names a different
   * problem and an operator wants to read all of them.
   *
   * <p>Deliberately not thread-safe: a latch never escapes the single thread that drives its walk
   * (the page loop blocks on each request), so a plain field is both sufficient and cheaper than an
   * atomic. Do not hoist an instance into a field — one per fetch is the whole contract.
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
   * Drops the cached page-1 {@code ETag} for a walk that did not produce a full census.
   *
   * <p>{@code fetchSinglePage} stores the {@code ETag} on <em>any</em> page-1 2xx, which is before
   * completeness can possibly be known — it is a property of the whole walk, not of page 1. Left
   * cached, an incomplete walk freezes that endpoint's conditional-GET state: the next run replays
   * {@code If-None-Match}, the upstream answers 304, the result is {@code unchanged()}, and the
   * pages this walk never fetched are never fetched again until the upstream bytes happen to
   * change. Since a not-modified pass also stands the cross-kind orphan sweep down, one bad walk
   * could otherwise suppress the sweep for that catalogue indefinitely — the shape seen on {@code
   * /api/vehicle-items} in the 2026-09-02 export, whose census warned identically on two runs 96
   * minutes apart.
   *
   * <p>Deliberately not called on the 304 early return: that ETag is still valid, and the whole
   * point of the conditional GET is to keep it.
   *
   * @param firstPageUri the canonical page-1 request URI this walk started from; never null.
   */
  private void forgetFirstPageEtag(String firstPageUri) {
    if (etagByFirstPageUri.remove(firstPageUri) != null) {
      log.debug(
          "Dropped the cached page-1 ETag after an incomplete walk so the next run re-fetches"
              + " unconditionally instead of being answered 304.");
    }
  }
}

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

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.backend.config.ScWikiProperties;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiBlueprintDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiCommodityDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiResponseDto;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Unit tests for {@link ScWikiClient} using {@link MockWebServer} to stand in for {@code
 * api.star-citizen.wiki}.
 *
 * <p>The four behaviours this fixture pins are the ones called out as SC Wiki-specific in {@code
 * SC_WIKI_SYNC_PLAN.md} §5.3:
 *
 * <ol>
 *   <li>Pagination loop — {@link #fetchAllPages_walksEveryPage_andMergesData()} walks page 1
 *       through page 3 and asserts the merged list size + the {@code ?page[number]=…} query arrived
 *       in order.
 *   <li>ETag conditional GET — {@link #etag304ShortCircuitOnFirstPage_returnsEmptyList()} primes
 *       the cache on a first call and verifies the second call sends {@code If-None-Match} and
 *       returns an empty list when the server replies 304.
 *   <li>Rate-limit pacing — {@link #paceForRateLimit_isInvokedBetweenPagesNotBeforeFirstPage()}
 *       subclasses the client with a counter-only override of {@link
 *       ScWikiClient#paceForRateLimit()} and asserts the pacing hook is invoked exactly {@code
 *       lastPage - 1} times (once between each adjacent page pair) and never before the first
 *       request.
 *   <li>Empty-response idempotence — {@link #emptyData_returnsEmptyListIdempotently()} and {@link
 *       #serverError_returnsEmptyListInsteadOfThrowing()} match the {@code UexClient} fallback
 *       contract.
 * </ol>
 */
class ScWikiClientTest {

  private MockWebServer server;
  private ScWikiProperties properties;
  private ScWikiClient client;
  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    properties = new ScWikiProperties();
    properties.setApiUrl(server.url("/").toString());
    properties.setPageSize(200);
    properties.setRequestsPerSecond(1000);
    meterRegistry = new SimpleMeterRegistry();
    client = new ScWikiClient(WebClient.builder(), properties, meterRegistry);
    client.initClient();
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  // ─── Pagination ─────────────────────────────────────────────────────────

  @Test
  void fetchAllPages_walksEveryPage_andMergesData() throws Exception {
    server.enqueue(
        jsonOk(
            pageBody(
                1,
                3,
                """
                {"uuid":"00000000-0000-0000-0000-000000000001","name":"Agricium"},
                {"uuid":"00000000-0000-0000-0000-000000000002","name":"Hadanite"}
                """)));
    server.enqueue(
        jsonOk(
            pageBody(
                2,
                3,
                """
                {"uuid":"00000000-0000-0000-0000-000000000003","name":"Quantanium"}
                """)));
    server.enqueue(
        jsonOk(
            pageBody(
                3,
                3,
                """
                {"uuid":"00000000-0000-0000-0000-000000000004","name":"Gold"},
                {"uuid":"00000000-0000-0000-0000-000000000005","name":"Iron"}
                """)));

    List<ScWikiCommodityDto> rows =
        client.fetchAllPages("/api/commodities", commodityTypeRef(), "commodities");

    assertEquals(5, rows.size(), "all three pages must be merged in order");
    assertEquals("Agricium", rows.get(0).name());
    assertEquals("Iron", rows.get(4).name());

    RecordedRequest p1 = server.takeRequest(1, TimeUnit.SECONDS);
    RecordedRequest p2 = server.takeRequest(1, TimeUnit.SECONDS);
    RecordedRequest p3 = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(p1);
    assertNotNull(p2);
    assertNotNull(p3);
    assertTrue(p1.getPath().contains("page%5Bnumber%5D=1"), "first request must target page 1");
    assertTrue(p2.getPath().contains("page%5Bnumber%5D=2"), "second request must target page 2");
    assertTrue(p3.getPath().contains("page%5Bnumber%5D=3"), "third request must target page 3");
    assertTrue(p1.getPath().contains("page%5Bsize%5D=200"), "page size must be sent on every page");
  }

  @Test
  void fetchAllPages_withIncludeQueryParam_propagatesIncludeOnFirstPage() throws Exception {
    server.enqueue(jsonOk(pageBody(1, 1, "")));

    client.fetchAllPages("/api/commodities", commodityTypeRef(), "commodities", "blueprints,items");

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    // Comma is in RFC 3986's sub-delims set and stays unencoded in query values when sent by
    // Spring's WebClient. Accept both forms — what matters is the wire-level include is present.
    String path = req.getPath();
    assertTrue(
        path.contains("include=blueprints,items") || path.contains("include=blueprints%2Citems"),
        "include= must be appended to the page-1 query string: " + path);
  }

  // ─── Per-endpoint page size ─────────────────────────────────────────────

  @Test
  void pageSizeOverride_isSentOnEveryPage_andDrivesTheFullPageCheck() throws Exception {
    // /api/vehicles answers 10.4 MB on page 1 at the shared page size of 200, against a 16 MB codec
    // ceiling whose overrun is swallowed into an empty list — a silent stop. The vehicle walk
    // therefore asks for a smaller page, and the override has to reach BOTH the wire and the
    // "was page 1 full?" contract check, which is what decides whether a missing meta.last_page is
    // a healthy single page or an un-walked remainder.
    properties.setPageSize(200);
    server.enqueue(jsonOk(pageBodyWithoutMeta(rows(2))));

    ScWikiClient.FetchResult<ScWikiCommodityDto> result =
        client.fetchAllPagesResult(
            "/api/commodities", commodityTypeRef(), "commodities", null, null, 2);

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    assertTrue(
        req.getPath().contains("page%5Bsize%5D=2"),
        "the override, not the configured 200, must go on the wire: " + req.getPath());
    assertFalse(
        result.complete(),
        "two rows fill a page of two, so a missing last_page is an un-walked remainder — judging"
            + " fullness against the configured 200 would have called this a complete census");
  }

  @Test
  void nonPositivePageSizeOverride_fallsBackToTheConfiguredDefault() throws Exception {
    // A misconfigured override must degrade to the default rather than ask the upstream for zero
    // rows and let the empty answer read as an outage.
    properties.setPageSize(200);
    server.enqueue(jsonOk(pageBody(1, 1, rows(1))));

    client.fetchAllPagesResult(
        "/api/commodities", commodityTypeRef(), "commodities", null, null, 0);

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    assertTrue(
        req.getPath().contains("page%5Bsize%5D=200"),
        "a non-positive override must fall back to the configured page size: " + req.getPath());
  }

  // ─── ETag 304 short-circuit ─────────────────────────────────────────────

  @Test
  void etag304ShortCircuitOnFirstPage_returnsEmptyList() throws Exception {
    server.enqueue(jsonOk(pageBody(1, 1, "")).setHeader("ETag", "\"wiki-v1\""));
    server.enqueue(new MockResponse().setResponseCode(304).setHeader("ETag", "\"wiki-v1\""));

    client.fetchAllPages("/api/commodities", commodityTypeRef(), "commodities"); // primes ETag
    List<ScWikiCommodityDto> second =
        client.fetchAllPages("/api/commodities", commodityTypeRef(), "commodities");

    assertTrue(second.isEmpty(), "304 on page 1 must short-circuit to empty list");
    server.takeRequest(1, TimeUnit.SECONDS); // first
    RecordedRequest secondReq = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(secondReq);
    assertEquals(
        "\"wiki-v1\"",
        secondReq.getHeader("If-None-Match"),
        "second call must replay the ETag stored from the first 200 response");
  }

  @Test
  void etagStorage_isPerUri_includeParamYieldsDifferentCacheKey() throws Exception {
    server.enqueue(jsonOk(pageBody(1, 1, "")).setHeader("ETag", "\"with-include\""));
    server.enqueue(jsonOk(pageBody(1, 1, "")));

    client.fetchAllPages("/api/commodities", commodityTypeRef(), "commodities", "blueprints");
    client.fetchAllPages("/api/commodities", commodityTypeRef(), "commodities");

    server.takeRequest(1, TimeUnit.SECONDS); // include=blueprints
    RecordedRequest noInclude = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(noInclude);
    assertNull(
        noInclude.getHeader("If-None-Match"),
        "the no-include call must NOT receive the include=blueprints ETag");
  }

  // ─── fetchAllPagesResult 304 vs empty/error flag (#1182) ────────────────

  @Test
  void fetchAllPagesResult_304OnFirstPage_isFlaggedNotModified() throws Exception {
    server.enqueue(jsonOk(pageBody(1, 1, "")).setHeader("ETag", "\"wiki-v1\""));
    server.enqueue(new MockResponse().setResponseCode(304).setHeader("ETag", "\"wiki-v1\""));

    client.fetchAllPagesResult(
        "/api/commodities", commodityTypeRef(), "commodities"); // primes ETag
    ScWikiClient.FetchResult<ScWikiCommodityDto> second =
        client.fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities");

    assertTrue(second.notModified(), "a 304 on page 1 must be surfaced as notModified");
    assertTrue(second.data().isEmpty(), "a 304 carries no rows");
  }

  @Test
  void fetchAllPagesResult_emptyDataAndError_areNotFlaggedNotModified() throws Exception {
    // A genuine empty-200 is a real (if empty) response, NOT a 304 — it must stay a zero-item
    // signal to SyncZeroItems.
    server.enqueue(jsonOk(pageBody(1, 1, "")));
    ScWikiClient.FetchResult<ScWikiCommodityDto> empty200 =
        client.fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities");
    assertFalse(empty200.notModified(), "an empty-200 must not be flagged notModified");
    assertTrue(empty200.data().isEmpty());

    // A 5xx error is likewise not a 304.
    server.enqueue(new MockResponse().setResponseCode(500).setBody("boom"));
    ScWikiClient.FetchResult<ScWikiCommodityDto> err =
        client.fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities");
    assertFalse(err.notModified(), "an error must not be flagged notModified");
    assertTrue(err.data().isEmpty());
  }

  @Test
  void fetchAllPagesResult_2xxWithRows_isFlaggedModifiedWithData() throws Exception {
    server.enqueue(
        jsonOk(
            pageBody(
                1,
                1,
                """
                {"uuid":"00000000-0000-0000-0000-0000000000f1","name":"Gold"}
                """)));

    ScWikiClient.FetchResult<ScWikiCommodityDto> result =
        client.fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities");

    assertFalse(result.notModified(), "a 2xx with rows is a modified response");
    assertEquals(1, result.data().size());
    assertEquals("Gold", result.data().get(0).name());
  }

  // ─── Rate-limit pacing hook ─────────────────────────────────────────────

  @Test
  void paceForRateLimit_isInvokedBetweenPagesNotBeforeFirstPage() throws Exception {
    server.enqueue(jsonOk(pageBody(1, 3, "")));
    server.enqueue(jsonOk(pageBody(2, 3, "")));
    server.enqueue(jsonOk(pageBody(3, 3, "")));

    AtomicInteger paceCalls = new AtomicInteger(0);
    ScWikiClient counter =
        new ScWikiClient(WebClient.builder(), properties, meterRegistry) {
          @Override
          public void paceForRateLimit() {
            paceCalls.incrementAndGet();
          }
        };
    counter.initClient();

    counter.fetchAllPages("/api/commodities", commodityTypeRef(), "commodities");

    assertEquals(
        2,
        paceCalls.get(),
        "pacing hook must be invoked between each adjacent page pair, never before the first");
  }

  // ─── Empty / error fallback ─────────────────────────────────────────────

  @Test
  void emptyData_returnsEmptyListIdempotently() throws Exception {
    server.enqueue(jsonOk(pageBody(1, 1, "")));

    List<ScWikiCommodityDto> rows =
        client.fetchAllPages("/api/commodities", commodityTypeRef(), "commodities");

    assertNotNull(rows);
    assertTrue(rows.isEmpty(), "empty data array must surface as an empty list, not null");
  }

  @Test
  void serverError_returnsEmptyListInsteadOfThrowing() {
    server.enqueue(new MockResponse().setResponseCode(500).setBody("upstream exploded"));

    List<ScWikiCommodityDto> rows =
        client.fetchAllPages("/api/commodities", commodityTypeRef(), "commodities");

    assertNotNull(rows, "fallback must return empty list, not null");
    assertTrue(rows.isEmpty());
    // The swallowed upstream failure must still leave a metric trail (REQ-OBS-011, #1041 item 2).
    assertEquals(
        1.0,
        meterRegistry
            .get(MetricNames.EXTERNAL_FETCH_ERRORS)
            .tag(MetricNames.TAG_SOURCE, MetricNames.SOURCE_SCWIKI)
            .counter()
            .count(),
        "a swallowed SC Wiki fetch error must increment"
            + " basetool_external_fetch_errors_total{source=scwiki}");
  }

  // ─── Census completeness (H5) ───────────────────────────────────────────
  // A half-walked feed still returns real rows, so the syncs' "did we see anything?" gate waves it
  // through and every row on the pages that were never fetched gets tombstoned as scwiki_deleted.
  // The complete flag is the caller's only way to tell "the Wiki dropped these" from "we never
  // asked", so these tests pin exactly when it must be false.

  @Test
  void fullFirstPageWithoutPaginationMetadata_isIncomplete_andWarns() {
    // The upstream-rename signature: meta absent (silently decoded to null), page 1 filled to the
    // configured page size. Assuming "one page" here would drop every later page on the floor.
    properties.setPageSize(3);
    server.enqueue(jsonOk(pageBodyWithoutMeta(rows(3))));

    ScWikiClient.FetchResult<ScWikiCommodityDto> result =
        client.fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities");

    assertFalse(
        result.complete(),
        "a full page 1 with no last_page must not be reported as a complete census");
    assertEquals(3, result.data().size(), "the rows that did arrive are still returned");
    assertEquals(
        1.0,
        fetchErrorCount(),
        "a missing pagination contract must increment"
            + " basetool_external_fetch_errors_total{source=scwiki}");
  }

  @Test
  void shortSinglePageWithoutPaginationMetadata_staysComplete() {
    // The healthy single-page case shares the "no last_page" shape but is NOT a contract break: the
    // page came back short, so there is demonstrably nothing after it. It must not warn, must not
    // count, and must stay sweepable — otherwise the guard would suppress every orphan sweep.
    properties.setPageSize(200);
    server.enqueue(jsonOk(pageBodyWithoutMeta(rows(2))));

    ScWikiClient.FetchResult<ScWikiCommodityDto> result =
        client.fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities");

    assertTrue(result.complete(), "a genuinely single-page result is a complete census");
    assertEquals(2, result.data().size());
    assertEquals(0.0, fetchErrorCount(), "a healthy short page is not a fetch error");
  }

  @Test
  void distinctRowsFallingShortOfMetaTotal_isIncomplete_andWarns() {
    // The upstream states 205 rows for this filter; the walk saw 1. Whatever the cause (a dropped
    // page, a feed that changed mid-walk), 204 rows the Wiki still lists are absent from the merged
    // list — the direction that gets live rows tombstoned, so it must not be sweepable.
    server.enqueue(jsonOk(pageBodyWithTotal(1, 1, 205, rows(1))));

    ScWikiClient.FetchResult<ScWikiCommodityDto> result =
        client.fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities");

    assertFalse(
        result.complete(), "a distinct-row count below meta.total is not a complete census");
    assertEquals(1, result.data().size(), "the fetched rows are still returned");
    assertEquals(1.0, fetchErrorCount(), "a total mismatch must count as a fetch error");
  }

  @Test
  void metaTotalMatchingTheDistinctRowCount_staysComplete() {
    server.enqueue(jsonOk(pageBodyWithTotal(1, 2, 3, rows(2))));
    server.enqueue(jsonOk(pageBodyWithTotal(2, 2, 3, rows(1, 3))));

    ScWikiClient.FetchResult<ScWikiCommodityDto> result =
        client.fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities");

    assertTrue(
        result.complete(), "a walk that saw exactly meta.total distinct rows is a full census");
    assertEquals(3, result.data().size());
    assertEquals(0.0, fetchErrorCount(), "a healthy full walk is not a fetch error");
  }

  @Test
  void distinctRowsExceedingMetaTotal_staysComplete_andDoesNotCount() {
    // The live /api/items shape (verified 2026-08-28): the paginator serves 12 331 distinct rows
    // across the 62 pages it announces while its own meta.total says 12 283 — a stable upstream
    // count that under-reports its own feed, on the one endpoint the residual GENERIC pass walks.
    // Every row was seen and none twice, so there is no gap for a tombstone sweep to fall into.
    // Reading the surplus as INCOMPLETE suppressed the cross-kind orphan sweep on every nightly run
    // and burned a daily external-fetch-error into ScWikiCensusIncompleteStreak.
    server.enqueue(jsonOk(pageBodyWithTotal(1, 1, 2, rows(3))));

    List<ScWikiClient.FetchResult<ScWikiCommodityDto>> captured = new ArrayList<>();
    List<ILoggingEvent> events =
        captureClientLog(
            () ->
                captured.add(
                    client.fetchAllPagesResult(
                        "/api/commodities", commodityTypeRef(), "commodities")));

    assertTrue(
        captured.get(0).complete(),
        "more distinct rows than meta.total claims cannot hide a row from the sweep — the census"
            + " stands");
    assertEquals(3, captured.get(0).data().size(), "every fetched row is still returned");
    assertEquals(
        0.0,
        fetchErrorCount(),
        "an upstream count that under-reports its own feed is not a fetch" + " error");
    assertTrue(
        events.stream().noneMatch(e -> e.getLevel() == Level.WARN),
        "a surplus must not WARN on every run: " + messages(events));
  }

  @Test
  void rowServedOnTwoPages_isIncomplete_evenWhenTheRowCountMatchesMetaTotal() {
    // Page 2 re-serves row 3 instead of row 4 — the shape a row inserted upstream mid-walk
    // produces, which shifts every later row across the page boundaries. The merged SIZE still
    // matches meta.total exactly, because the duplicate and the omission cancel out: a size-only
    // cross-check calls this a full census and row 4, which was never fetched, becomes a tombstone
    // candidate. Only the distinct count can see it.
    server.enqueue(jsonOk(pageBodyWithTotal(1, 2, 4, rows(3))));
    server.enqueue(jsonOk(pageBodyWithTotal(2, 2, 4, rows(1, 3))));

    List<ScWikiClient.FetchResult<ScWikiCommodityDto>> captured = new ArrayList<>();
    List<ILoggingEvent> events =
        captureClientLog(
            () ->
                captured.add(
                    client.fetchAllPagesResult(
                        "/api/commodities", commodityTypeRef(), "commodities")));

    assertFalse(
        captured.get(0).complete(),
        "a walk that was served the same row twice never enumerated the feed, however well the"
            + " totals line up");
    assertEquals(4, captured.get(0).data().size(), "the rows that did arrive are still returned");
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("only 3 distinct")),
        "the repeated row must WARN with both counts: " + messages(events));
    assertEquals(
        1.0,
        fetchErrorCount(),
        "the repetition and the shortfall it causes are one failed fetch, not two");
  }

  @Test
  void feedAnnouncingMorePagesByTheEndOfTheWalk_isIncomplete() {
    // The loop bound is fixed when page 1 answers. Page 2 comes back announcing a page 3 that was
    // therefore never requested — the same "we never asked" case as a dropped page, and the guard
    // that lets a surplus stand above without letting a growing feed slip through with it.
    server.enqueue(jsonOk(pageBodyWithTotal(1, 2, 4, rows(2))));
    server.enqueue(jsonOk(pageBodyWithTotal(2, 3, 6, rows(2, 3))));

    ScWikiClient.FetchResult<ScWikiCommodityDto> result =
        client.fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities");

    assertFalse(
        result.complete(),
        "the pages past the original bound were never fetched, so nothing may"
            + " be tombstoned for missing from the merged list");
    assertEquals(4, result.data().size(), "the two walked pages are still returned");
    assertEquals(1.0, fetchErrorCount(), "an un-walked tail must leave a metric trail");
  }

  @Test
  void rowsWithoutUuidsAreNotMistakenForOneRowRepeated() {
    // An endpoint that serves rows without a uuid has no identity to deduplicate on. Collapsing
    // them would report 1 distinct row for 3, i.e. a permanent "the feed repeated itself" verdict
    // on an entirely healthy walk.
    server.enqueue(jsonOk(pageBodyWithTotal(1, 1, 3, idlessRows(3))));

    ScWikiClient.FetchResult<ScWikiCommodityDto> result =
        client.fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities");

    assertTrue(result.complete(), "id-less rows each count as their own row, not as duplicates");
    assertEquals(3, result.data().size());
    assertEquals(0.0, fetchErrorCount(), "a healthy id-less feed is not a fetch error");
  }

  @Test
  void twoCensusProblemsInOneWalk_warnSeparatelyButCountAsOneFetchError() {
    // A renamed meta block loses last_page and total together, so "no pagination metadata on a full
    // page 1" and "meta.total disagrees with the merged rows" are the same single upstream failure
    // seen twice. Both WARNs must survive — they name different problems and an operator wants both
    // — but the counter tracks failed FETCHES, not symptoms: counting each would inflate
    // basetool_external_fetch_errors_total by the number of things that happened to be wrong.
    properties.setPageSize(3);
    server.enqueue(jsonOk(pageBodyWithTotalWithoutLastPage(205, rows(3))));

    List<ILoggingEvent> events =
        captureClientLog(
            () ->
                assertFalse(
                    client
                        .fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities")
                        .complete(),
                    "a walk with two census problems is not a complete census"));

    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("no pagination metadata")),
        "the missing-last_page problem must still WARN: " + messages(events));
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("meta.total reports")),
        "the total-mismatch problem must still WARN: " + messages(events));
    assertEquals(
        1.0,
        fetchErrorCount(),
        "one fetch exhibiting two problems is ONE failed fetch —"
            + " basetool_external_fetch_errors_total{source=scwiki} must not double-count");
  }

  @Test
  void midWalkFailureAndResultingTotalMismatch_countAsOneFetchError() {
    // The other double-count pairing: the dropped page is itself what makes the merged rows fall
    // short of meta.total, so the failed page fetch and the mismatch are one and the same failure.
    server.enqueue(jsonOk(pageBodyWithTotal(1, 3, 3, rows(1))));
    server.enqueue(new MockResponse().setResponseCode(503));

    List<ILoggingEvent> events =
        captureClientLog(
            () ->
                assertFalse(
                    client
                        .fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities")
                        .complete(),
                    "a walk abandoned on page 2 of 3 is not a complete census"));

    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("failed mid-pagination")),
        "the dropped page must still WARN: " + messages(events));
    assertTrue(
        events.stream()
            .anyMatch(
                e ->
                    e.getLevel() == Level.WARN
                        && e.getFormattedMessage().contains("meta.total reports")),
        "the resulting total mismatch must still WARN: " + messages(events));
    assertEquals(
        1.0,
        fetchErrorCount(),
        "a dropped page and the row-count shortfall it causes are one failed fetch, not two");
  }

  @Test
  void pageFailingMidWalk_isIncomplete() {
    server.enqueue(jsonOk(pageBody(1, 3, rows(1))));
    server.enqueue(new MockResponse().setResponseCode(503));

    ScWikiClient.FetchResult<ScWikiCommodityDto> result =
        client.fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities");

    assertFalse(result.complete(), "a walk abandoned on page 2 of 3 is not a complete census");
    assertEquals(1, result.data().size(), "page 1's row survives the page-2 failure");
  }

  @Test
  void failedFirstPage_isIncomplete() {
    server.enqueue(new MockResponse().setResponseCode(500));

    ScWikiClient.FetchResult<ScWikiCommodityDto> result =
        client.fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities");

    assertFalse(result.complete(), "a failed page 1 enumerated nothing at all");
    assertTrue(result.data().isEmpty());
    assertFalse(result.notModified(), "an error is not an unchanged catalogue");
  }

  @Test
  void unchanged304_isNotReportedAsACompleteCensus() {
    // Belt and braces: every caller checks notModified() first, but a 304 enumerates nothing, so a
    // caller that forgot must still be unable to sweep.
    server.enqueue(jsonOk(pageBody(1, 1, "")).setHeader("ETag", "\"v1\""));
    client.fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities");
    server.enqueue(new MockResponse().setResponseCode(304).setHeader("ETag", "\"v1\""));

    ScWikiClient.FetchResult<ScWikiCommodityDto> result =
        client.fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities");

    assertTrue(result.notModified());
    assertFalse(result.complete(), "a conditional-GET hit enumerated no rows");
  }

  @Test
  void midPagePartialFailure_returnsAccumulatedRowsSoFar() throws Exception {
    server.enqueue(
        jsonOk(
            pageBody(
                1,
                3,
                """
                {"uuid":"00000000-0000-0000-0000-0000000000a1","name":"Iron"}
                """)));
    server.enqueue(new MockResponse().setResponseCode(503));

    List<ScWikiCommodityDto> rows =
        client.fetchAllPages("/api/commodities", commodityTypeRef(), "commodities");

    assertEquals(
        1,
        rows.size(),
        "page-1 succeeded with one row; the page-2 5xx must not wipe what we already have");
    assertEquals("Iron", rows.get(0).name());
  }

  // ─── fetchOne single-resource bind ──────────────────────────────────────

  @Test
  void fetchOne_dataWrappedDetail_bindsThroughRealCodec() throws Exception {
    // Regression: Spring Boot 4 wires the WebClient to the Jackson 3 codec, which cannot construct
    // a
    // Jackson 2 JsonNode — the previous bodyToMono(JsonNode.class) aborted every blueprint/item
    // detail fetch with "Cannot construct instance of JsonNode". This binds the {data:{…}} envelope
    // end-to-end through that real codec, so a reintroduction fails here instead of only in prod.
    server.enqueue(
        jsonOk(
            """
            {"data":{"uuid":"00000000-0000-0000-0000-0000000000bb",
            "key":"BP_CRAFT_TEST","output_name":"Test Output"}}
            """));

    ScWikiBlueprintDto detail =
        client.fetchOne(
            "/api/blueprints/00000000-0000-0000-0000-0000000000bb",
            ScWikiBlueprintDto.class,
            "blueprint");

    assertNotNull(detail, "data-wrapped detail must bind, not fail the Jackson codec");
    assertEquals("BP_CRAFT_TEST", detail.key());
    assertEquals("Test Output", detail.outputName());
  }

  @Test
  void fetchOne_flatDetailWithoutDataEnvelope_binds() throws Exception {
    server.enqueue(
        jsonOk(
            """
            {"uuid":"00000000-0000-0000-0000-0000000000cc","key":"BP_FLAT","output_name":"Flat"}
            """));

    ScWikiBlueprintDto detail =
        client.fetchOne(
            "/api/blueprints/00000000-0000-0000-0000-0000000000cc",
            ScWikiBlueprintDto.class,
            "blueprint");

    assertNotNull(detail, "a flat (un-enveloped) body must bind too");
    assertEquals("BP_FLAT", detail.key());
  }

  @Test
  void fetchOne_notFound_returnsNull() {
    server.enqueue(new MockResponse().setResponseCode(404));

    ScWikiBlueprintDto detail =
        client.fetchOne("/api/blueprints/missing", ScWikiBlueprintDto.class, "blueprint");

    assertNull(detail, "404 must resolve to null (Wiki doesn't know this one), not throw");
  }

  // ─── helpers ────────────────────────────────────────────────────────────

  // Current basetool_external_fetch_errors_total{source=scwiki}, tolerating an unregistered
  // counter (nothing failed yet) as 0.
  private double fetchErrorCount() {
    return meterRegistry
        .find(MetricNames.EXTERNAL_FETCH_ERRORS)
        .tag(MetricNames.TAG_SOURCE, MetricNames.SOURCE_SCWIKI)
        .counters()
        .stream()
        .mapToDouble(io.micrometer.core.instrument.Counter::count)
        .sum();
  }

  private MockResponse jsonOk(String body) {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }

  // A page whose meta carries no "total". The client's census cross-check only fires when the
  // upstream states a total, so these fixtures exercise the pagination itself without also having
  // to state a row count the test does not care about.
  private String pageBody(int currentPage, int lastPage, String dataCommaSeparated) {
    String data = dataCommaSeparated == null ? "" : dataCommaSeparated.trim();
    return """
    {
      "data": [%s],
      "links": {"first":"","last":"","prev":null,"next":null},
      "meta": {"current_page":%d,"last_page":%d,"per_page":200}
    }
    """
        .formatted(data, currentPage, lastPage);
  }

  // A page whose meta states the upstream's own row count for the whole feed — the value the
  // client cross-checks the merged list against.
  private String pageBodyWithTotal(
      int currentPage, int lastPage, int total, String dataCommaSeparated) {
    String data = dataCommaSeparated == null ? "" : dataCommaSeparated.trim();
    return """
    {
      "data": [%s],
      "links": {"first":"","last":"","prev":null,"next":null},
      "meta": {"current_page":%d,"last_page":%d,"per_page":200,"total":%d}
    }
    """
        .formatted(data, currentPage, lastPage, total);
  }

  // A 2xx page whose meta states a total but has LOST last_page — the shape a partially renamed
  // meta block produces, and the one that makes a single fetch trip two census problems at once
  // (full page 1 without a page count, and a distinct row count below the stated total).
  private String pageBodyWithTotalWithoutLastPage(int total, String dataCommaSeparated) {
    String data = dataCommaSeparated == null ? "" : dataCommaSeparated.trim();
    return """
    {
      "data": [%s],
      "links": {"first":"","last":"","prev":null,"next":null},
      "meta": {"current_page":1,"per_page":3,"total":%d}
    }
    """
        .formatted(data, total);
  }

  // A 2xx page with data but NO meta object at all — the shape an upstream field rename produces,
  // since ScWikiResponseDto/ScWikiMetaDto are @JsonIgnoreProperties(ignoreUnknown = true) and
  // decode
  // the renamed field to null instead of failing.
  private String pageBodyWithoutMeta(String dataCommaSeparated) {
    String data = dataCommaSeparated == null ? "" : dataCommaSeparated.trim();
    return """
    {
      "data": [%s],
      "links": {"first":"","last":"","prev":null,"next":null}
    }
    """
        .formatted(data);
  }

  // `count` comma-separated commodity rows with distinct synthetic uuids, numbered from 1.
  private static String rows(int count) {
    return rows(count, 1);
  }

  // `count` comma-separated commodity rows numbered from `firstIndex`. Multi-page fixtures MUST
  // number their pages consecutively: the client counts DISTINCT uuids to decide whether a walk
  // enumerated the feed, so a second page that restarts at 1 is a feed that served the same rows
  // twice — which is exactly what the census check exists to reject.
  private static String rows(int count, int firstIndex) {
    StringBuilder sb = new StringBuilder();
    for (int i = firstIndex; i < firstIndex + count; i++) {
      if (i > firstIndex) {
        sb.append(",\n");
      }
      sb.append("{\"uuid\":\"00000000-0000-0000-0000-%012d\",\"name\":\"Row%d\"}".formatted(i, i));
    }
    return sb.toString();
  }

  // `count` comma-separated commodity rows the upstream served WITHOUT a uuid — the shape that
  // must NOT read as one row repeated `count` times to the distinct-row census.
  private static String idlessRows(int count) {
    StringBuilder sb = new StringBuilder();
    for (int i = 1; i <= count; i++) {
      if (i > 1) {
        sb.append(",\n");
      }
      sb.append("{\"name\":\"Row%d\"}".formatted(i));
    }
    return sb.toString();
  }

  // Runs `call` with a ListAppender attached to the ScWikiClient logger and returns everything the
  // client logged while it ran. Mirrors UexClientTest.captureUexLog.
  private List<ILoggingEvent> captureClientLog(Runnable call) {
    Logger clientLog = (Logger) LoggerFactory.getLogger(ScWikiClient.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    clientLog.addAppender(appender);
    try {
      call.run();
      return List.copyOf(appender.list);
    } finally {
      clientLog.detachAppender(appender);
    }
  }

  // Captured log events as one assertion-message-friendly string.
  private static String messages(List<ILoggingEvent> events) {
    return events.stream()
        .map(e -> e.getLevel() + " " + e.getFormattedMessage())
        .toList()
        .toString();
  }

  private static ParameterizedTypeReference<ScWikiResponseDto<ScWikiCommodityDto>>
      commodityTypeRef() {
    return new ParameterizedTypeReference<>() {};
  }
}

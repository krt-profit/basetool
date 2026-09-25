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
import de.greluc.krt.profit.basetool.backend.config.RestClientConfig;
import de.greluc.krt.profit.basetool.backend.config.ScWikiProperties;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiBlueprintDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiCommodityDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiResponseDto;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

  /** The configured keys, relative to the record's prefix; {@link #rebuild()} binds them. */
  private final Map<String, Object> config = new HashMap<>();

  private ScWikiClient client;
  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    config.putAll(
        Map.of(
            "api-url", server.url("/").toString(), "page-size", 200, "requests-per-second", 1000));
    meterRegistry = new SimpleMeterRegistry();
    rebuild();
  }

  /**
   * Binds the properties record from {@link #config} and builds the object under test over it. The
   * record is immutable (BE-MOD-04), so a test that changes a key rebuilds.
   */
  private void rebuild() {
    properties = BoundProperties.bind(ScWikiProperties.class, config);
    client =
        new ScWikiClient(
            new RestClientConfig().restClientBuilder(ObservationRegistry.NOOP),
            properties,
            meterRegistry);
    client.initClient();
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

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
    String path = req.getPath();
    assertTrue(
        path.contains("include=blueprints,items") || path.contains("include=blueprints%2Citems"),
        "include= must be appended to the page-1 query string: " + path);
  }

  @Test
  void pageSizeOverride_isSentOnEveryPage_andDrivesTheFullPageCheck() throws Exception {
    config.put("page-size", 200);
    rebuild();
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
    config.put("page-size", 200);
    rebuild();
    server.enqueue(jsonOk(pageBody(1, 1, rows(1))));

    client.fetchAllPagesResult(
        "/api/commodities", commodityTypeRef(), "commodities", null, null, 0);

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    assertTrue(
        req.getPath().contains("page%5Bsize%5D=200"),
        "a non-positive override must fall back to the configured page size: " + req.getPath());
  }

  @Test
  void etag304ShortCircuitOnFirstPage_returnsEmptyList() throws Exception {
    server.enqueue(jsonOk(pageBody(1, 1, "")).setHeader("ETag", "\"wiki-v1\""));
    server.enqueue(new MockResponse().setResponseCode(304).setHeader("ETag", "\"wiki-v1\""));

    client.fetchAllPages("/api/commodities", commodityTypeRef(), "commodities");
    List<ScWikiCommodityDto> second =
        client.fetchAllPages("/api/commodities", commodityTypeRef(), "commodities");

    assertTrue(second.isEmpty(), "304 on page 1 must short-circuit to empty list");
    server.takeRequest(1, TimeUnit.SECONDS);
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

    server.takeRequest(1, TimeUnit.SECONDS);
    RecordedRequest noInclude = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(noInclude);
    assertNull(
        noInclude.getHeader("If-None-Match"),
        "the no-include call must NOT receive the include=blueprints ETag");
  }

  @Test
  void fetchAllPagesResult_304OnFirstPage_isFlaggedNotModified() throws Exception {
    server.enqueue(jsonOk(pageBody(1, 1, "")).setHeader("ETag", "\"wiki-v1\""));
    server.enqueue(new MockResponse().setResponseCode(304).setHeader("ETag", "\"wiki-v1\""));

    client.fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities");
    ScWikiClient.FetchResult<ScWikiCommodityDto> second =
        client.fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities");

    assertTrue(second.notModified(), "a 304 on page 1 must be surfaced as notModified");
    assertTrue(second.data().isEmpty(), "a 304 carries no rows");
  }

  @Test
  void fetchAllPagesResult_emptyDataAndError_areNotFlaggedNotModified() throws Exception {
    server.enqueue(jsonOk(pageBody(1, 1, "")));
    ScWikiClient.FetchResult<ScWikiCommodityDto> empty200 =
        client.fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities");
    assertFalse(empty200.notModified(), "an empty-200 must not be flagged notModified");
    assertTrue(empty200.data().isEmpty());

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

  @Test
  void paceForRateLimit_isInvokedBetweenPagesNotBeforeFirstPage() throws Exception {
    server.enqueue(jsonOk(pageBody(1, 3, "")));
    server.enqueue(jsonOk(pageBody(2, 3, "")));
    server.enqueue(jsonOk(pageBody(3, 3, "")));

    AtomicInteger paceCalls = new AtomicInteger(0);
    ScWikiClient counter =
        new ScWikiClient(
            new RestClientConfig().restClientBuilder(ObservationRegistry.NOOP),
            properties,
            meterRegistry) {
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

  @Test
  void fullFirstPageWithoutPaginationMetadata_isIncomplete_andWarns() {
    config.put("page-size", 3);
    rebuild();
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
    config.put("page-size", 200);
    rebuild();
    server.enqueue(jsonOk(pageBodyWithoutMeta(rows(2))));

    ScWikiClient.FetchResult<ScWikiCommodityDto> result =
        client.fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities");

    assertTrue(result.complete(), "a genuinely single-page result is a complete census");
    assertEquals(2, result.data().size());
    assertEquals(0.0, fetchErrorCount(), "a healthy short page is not a fetch error");
  }

  @Test
  void distinctRowsFallingShortOfMetaTotal_isIncomplete_andWarns() {
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
    server.enqueue(jsonOk(pageBodyWithTotal(1, 1, 3, idlessRows(3))));

    ScWikiClient.FetchResult<ScWikiCommodityDto> result =
        client.fetchAllPagesResult("/api/commodities", commodityTypeRef(), "commodities");

    assertTrue(result.complete(), "id-less rows each count as their own row, not as duplicates");
    assertEquals(3, result.data().size());
    assertEquals(0.0, fetchErrorCount(), "a healthy id-less feed is not a fetch error");
  }

  @Test
  void twoCensusProblemsInOneWalk_warnSeparatelyButCountAsOneFetchError() {
    config.put("page-size", 3);
    rebuild();
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

  @Test
  void fetchOne_dataWrappedDetail_bindsThroughRealCodec() throws Exception {
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

  private static String rows(int count) {
    return rows(count, 1);
  }

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

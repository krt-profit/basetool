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

import de.greluc.krt.profit.basetool.backend.config.ScWikiProperties;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiBlueprintDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiCommodityDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiResponseDto;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
      "meta": {"current_page":%d,"last_page":%d,"per_page":200,"total":1}
    }
    """
        .formatted(data, currentPage, lastPage);
  }

  private static ParameterizedTypeReference<ScWikiResponseDto<ScWikiCommodityDto>>
      commodityTypeRef() {
    return new ParameterizedTypeReference<>() {};
  }
}

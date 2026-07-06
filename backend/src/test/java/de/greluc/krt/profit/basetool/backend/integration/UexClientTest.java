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

package de.greluc.krt.profit.basetool.backend.integration;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.backend.config.UexProperties;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexCommodityDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexCommodityPriceDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexStarSystemDto;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Unit tests for the {@link UexClient}. Uses {@link MockWebServer} to drive the WebClient against
 * an in-process HTTP endpoint instead of hitting the real UEX API.
 *
 * <p>Three behaviour patterns to cover for every endpoint method:
 *
 * <ol>
 *   <li>Happy path — JSON wrapped in {@code {"status":"ok","data":[...]}} → list contents are
 *       parsed and returned.
 *   <li>Server error (5xx, network blip) → the {@code onErrorResume} fallback returns an empty list
 *       so the caller never sees an exception.
 *   <li>Empty body / no {@code data} field → empty list.
 * </ol>
 *
 * Each test pins the endpoint URI so a future refactor that accidentally swaps two endpoint
 * constants in {@link UexProperties} would fail loud.
 */
class UexClientTest {

  private MockWebServer server;
  private UexProperties properties;
  private UexClient client;
  private SimpleMeterRegistry meterRegistry;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();

    properties = new UexProperties();
    properties.setApiUrl(server.url("/").toString());
    // All endpoints stay at their default paths — the production defaults
    // already match the public UEX 2.0 API surface and are validated as
    // part of property-binding tests.

    meterRegistry = new SimpleMeterRegistry();
    client = new UexClient(WebClient.builder(), properties, meterRegistry);
    client.initClient();
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  // ─── getCommodities ─────────────────────────────────────────────────────

  @Test
  void getCommodities_happyPath_returnsParsedList() throws Exception {
    // Given
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody(
                """
                {
                  "status": "ok",
                  "data": [
                    {"id": 1, "name": "Gold", "is_illegal": 0},
                    {"id": 2, "name": "Quantanium", "is_illegal": 1}
                  ]
                }
                """));

    // When
    List<UexCommodityDto> commodities = client.getCommodities();

    // Then
    assertEquals(2, commodities.size());
    assertEquals("Gold", commodities.get(0).name());
    assertEquals("Quantanium", commodities.get(1).name());
    assertEquals(1, commodities.get(1).isIllegal());

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    assertEquals("/commodities", req.getPath());
    assertEquals("GET", req.getMethod());
  }

  @Test
  void getCommodities_serverError_returnsEmptyListInsteadOfThrowing() {
    // Given — a 5xx that the fallback must swallow
    server.enqueue(new MockResponse().setResponseCode(500).setBody("upstream exploded"));

    // When
    List<UexCommodityDto> commodities = client.getCommodities();

    // Then
    assertNotNull(commodities, "fallback must return empty list, not null");
    assertTrue(commodities.isEmpty());
    // The swallowed upstream failure must still leave a metric trail (REQ-OBS-011, #1041 item 2).
    assertEquals(
        1.0,
        meterRegistry
            .get(MetricNames.EXTERNAL_FETCH_ERRORS)
            .tag(MetricNames.TAG_SOURCE, MetricNames.SOURCE_UEX)
            .counter()
            .count(),
        "a swallowed UEX fetch error must increment"
            + " basetool_external_fetch_errors_total{source=uex}");
  }

  @Test
  void getCommodities_connectionDropped_returnsEmptyList() {
    // Given — simulate a network blip
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY));

    // When
    List<UexCommodityDto> commodities = client.getCommodities();

    // Then
    assertNotNull(commodities);
    assertTrue(commodities.isEmpty());
  }

  @Test
  void getCommodities_emptyDataArray_returnsEmptyList() {
    // Given — API returned a 200 but no items
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"status\":\"ok\",\"data\":[]}"));

    // When
    List<UexCommodityDto> commodities = client.getCommodities();

    // Then
    assertNotNull(commodities);
    assertTrue(commodities.isEmpty());
  }

  @Test
  void getCommodities_nullDataEnvelope_returnsEmptyListWithoutLoggingAnError() {
    // UEX sometimes returns {"status":"ok","data":null} for an empty category; the old
    // .map(UexResponseDto::data) emitted null and Reactor rejected it with a logged NPE.
    Logger uexLog = (Logger) LoggerFactory.getLogger(UexClient.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    uexLog.addAppender(appender);
    try {
      server.enqueue(jsonOk("{\"status\":\"ok\",\"data\":null}"));

      List<UexCommodityDto> commodities = client.getCommodities();

      assertNotNull(commodities, "null data must surface as an empty list, not null");
      assertTrue(commodities.isEmpty());
      assertTrue(
          appender.list.stream().noneMatch(e -> e.getLevel() == Level.ERROR),
          "null data is not a fetch failure — it must not be logged as ERROR");
    } finally {
      uexLog.detachAppender(appender);
    }
  }

  // ─── getCommoditiesPricesAll ────────────────────────────────────────────

  @Test
  void getCommoditiesPricesAll_happyPath_returnsParsedList() throws Exception {
    server.enqueue(
        jsonOk(
            """
            {"status":"ok","data":[
              {"id_commodity": 1, "price_buy": 12.5, "price_sell": 15.0}
            ]}
            """));

    List<UexCommodityPriceDto> prices = client.getCommoditiesPricesAll();

    assertEquals(1, prices.size());
    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("/commodities_prices_all", req.getPath());
  }

  @Test
  void getCommoditiesPricesAll_serverError_returnsEmptyList() {
    server.enqueue(new MockResponse().setResponseCode(503));
    assertTrue(client.getCommoditiesPricesAll().isEmpty());
  }

  // ─── getStarSystems ─────────────────────────────────────────────────────

  @Test
  void getStarSystems_happyPath_returnsParsedList() throws Exception {
    server.enqueue(
        jsonOk(
            """
            {"status":"ok","data":[
              {"id": 1, "name": "Stanton"},
              {"id": 2, "name": "Pyro"}
            ]}
            """));

    List<UexStarSystemDto> systems = client.getStarSystems();

    assertEquals(2, systems.size());
    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("/star_systems", req.getPath());
  }

  @Test
  void getStarSystems_clientError_returnsEmptyList() {
    server.enqueue(new MockResponse().setResponseCode(404));
    assertTrue(client.getStarSystems().isEmpty());
  }

  // ─── Endpoint sanity (URIs and empty-fallback) ─────────────────────────
  // These tests make sure all the smaller "list everything" endpoints hit
  // the right URI on the wire. We don't need separate happy-path schema
  // assertions for every Dto type — UexResponseDto<T> is generic and
  // Jackson's record-binding is already exercised by the three big ones
  // above. Each call gets an empty-data response so the fallback path
  // doesn't fire.

  @Test
  void getCompanies_hitsCorrectEndpoint() throws Exception {
    assertHitsEndpoint(client::getCompanies, "/companies");
  }

  @Test
  void getVehicles_hitsCorrectEndpoint() throws Exception {
    assertHitsEndpoint(client::getVehicles, "/vehicles");
  }

  @Test
  void getCities_hitsCorrectEndpoint() throws Exception {
    assertHitsEndpoint(client::getCities, "/cities");
  }

  @Test
  void getFactions_hitsCorrectEndpoint() throws Exception {
    assertHitsEndpoint(client::getFactions, "/factions");
  }

  @Test
  void getJurisdictions_hitsCorrectEndpoint() throws Exception {
    assertHitsEndpoint(client::getJurisdictions, "/jurisdictions");
  }

  @Test
  void getMoons_hitsCorrectEndpoint() throws Exception {
    assertHitsEndpoint(client::getMoons, "/moons");
  }

  @Test
  void getOrbits_hitsCorrectEndpoint() throws Exception {
    assertHitsEndpoint(client::getOrbits, "/orbits");
  }

  @Test
  void getOutposts_hitsCorrectEndpoint() throws Exception {
    assertHitsEndpoint(client::getOutposts, "/outposts");
  }

  @Test
  void getPlanets_hitsCorrectEndpoint() throws Exception {
    assertHitsEndpoint(client::getPlanets, "/planets");
  }

  @Test
  void getPoi_hitsCorrectEndpoint() throws Exception {
    assertHitsEndpoint(client::getPoi, "/poi");
  }

  @Test
  void getSpaceStations_hitsCorrectEndpoint() throws Exception {
    assertHitsEndpoint(client::getSpaceStations, "/space_stations");
  }

  @Test
  void getTerminals_hitsCorrectEndpoint() throws Exception {
    assertHitsEndpoint(client::getTerminals, "/terminals");
  }

  @Test
  void getRefineriesMethods_hitsCorrectEndpoint() throws Exception {
    assertHitsEndpoint(client::getRefineriesMethods, "/refineries_methods");
  }

  @Test
  void getRefineriesYields_hitsCorrectEndpoint() throws Exception {
    assertHitsEndpoint(client::getRefineriesYields, "/refineries_yields");
  }

  // ─── ETag conditional GET (M-5) ─────────────────────────────────────────
  // The fetchList helper captures the response ETag and replays it as
  // If-None-Match on the next request to the same endpoint. A 304 short-
  // circuits with an empty list (sync services treat that as "skip this
  // run"). A 200 with a new ETag overwrites the stored value so the next
  // call uses the fresh one. The behaviour is per-endpoint, so star-system
  // and commodity ETags do not interfere.

  @Test
  void firstCall_sendsNoIfNoneMatch_andRemembersResponseEtag() throws Exception {
    server.enqueue(jsonOk("{\"status\":\"ok\",\"data\":[]}").setHeader("ETag", "\"abc-123\""));
    server.enqueue(new MockResponse().setResponseCode(304).setHeader("ETag", "\"abc-123\""));

    client.getCommodities(); // primes the ETag store
    List<UexCommodityDto> second = client.getCommodities(); // replays the ETag

    RecordedRequest first = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(first);
    assertNull(
        first.getHeader("If-None-Match"),
        "first call must not send If-None-Match (nothing stored yet)");

    RecordedRequest secondReq = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(secondReq);
    assertEquals(
        "\"abc-123\"",
        secondReq.getHeader("If-None-Match"),
        "second call must replay the ETag from the first response");
    assertTrue(second.isEmpty(), "304 response must surface as an empty list");
  }

  @Test
  void notModifiedResponse_returnsEmptyListWithoutDecodingBody() {
    // 304 responses carry no body. The helper must not try to parse one
    // (the previous .retrieve().bodyToMono(...) chain would have thrown a
    // DecodingException on the missing body and dropped to the fallback,
    // which still returned empty - this test pins the explicit short-circuit
    // so the cleaner path stays intact).
    server.enqueue(new MockResponse().setResponseCode(304));

    List<UexCommodityDto> result = client.getCommodities();

    assertNotNull(result);
    assertTrue(result.isEmpty(), "304 Not Modified must yield an empty list");
  }

  @Test
  void updatedEtagOnNewResponse_replacesPreviouslyStoredEtag() throws Exception {
    server.enqueue(jsonOk("{\"status\":\"ok\",\"data\":[]}").setHeader("ETag", "\"v1\""));
    server.enqueue(jsonOk("{\"status\":\"ok\",\"data\":[]}").setHeader("ETag", "\"v2\""));
    server.enqueue(new MockResponse().setResponseCode(304).setHeader("ETag", "\"v2\""));

    client.getCommodities(); // stores v1
    client.getCommodities(); // sends v1, server returns 200 + v2 → stores v2
    client.getCommodities(); // sends v2

    server.takeRequest(1, TimeUnit.SECONDS); // discard the first
    RecordedRequest secondReq = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("\"v1\"", secondReq.getHeader("If-None-Match"));
    RecordedRequest thirdReq = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals(
        "\"v2\"",
        thirdReq.getHeader("If-None-Match"),
        "third call must use the v2 ETag from the second response");
  }

  @Test
  void etagStorage_isPerEndpoint_starSystemsEtagDoesNotLeakIntoCommodities() throws Exception {
    server.enqueue(jsonOk("{\"status\":\"ok\",\"data\":[]}").setHeader("ETag", "\"star-sys-1\""));
    server.enqueue(jsonOk("{\"status\":\"ok\",\"data\":[]}"));

    client.getStarSystems(); // stores ETag under /star_systems
    client.getCommodities(); // /commodities — different key, no ETag

    server.takeRequest(1, TimeUnit.SECONDS); // star_systems
    RecordedRequest commoditiesReq = server.takeRequest(1, TimeUnit.SECONDS);
    assertNull(
        commoditiesReq.getHeader("If-None-Match"),
        "/commodities must not receive the /star_systems ETag");
  }

  @Test
  void serverErrorClearsNoStoredEtag_andLeavesCachedValueIntact() throws Exception {
    server.enqueue(jsonOk("{\"status\":\"ok\",\"data\":[]}").setHeader("ETag", "\"keep-me\""));
    server.enqueue(new MockResponse().setResponseCode(500));
    server.enqueue(new MockResponse().setResponseCode(304).setHeader("ETag", "\"keep-me\""));

    client.getCommodities(); // stores keep-me
    List<UexCommodityDto> midError = client.getCommodities(); // 500 - fallback empty
    List<UexCommodityDto> thirdCall = client.getCommodities(); // should still send keep-me

    assertTrue(midError.isEmpty(), "5xx must still surface as empty list");
    assertTrue(thirdCall.isEmpty(), "subsequent 304 also yields empty list");

    server.takeRequest(1, TimeUnit.SECONDS); // first
    server.takeRequest(1, TimeUnit.SECONDS); // mid error - request was issued, response was 500
    RecordedRequest thirdReq = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals(
        "\"keep-me\"",
        thirdReq.getHeader("If-None-Match"),
        "a server error in between must not clear the stored ETag (it was not invalidated)");
  }

  // ─── helpers ────────────────────────────────────────────────────────────

  private MockResponse jsonOk(String body) {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }

  private void assertHitsEndpoint(Runnable call, String expectedPath) throws InterruptedException {
    server.enqueue(jsonOk("{\"status\":\"ok\",\"data\":[]}"));
    call.run();
    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req, "client did not issue an HTTP request");
    assertEquals(expectedPath, req.getPath());
    assertEquals("GET", req.getMethod());
  }
}

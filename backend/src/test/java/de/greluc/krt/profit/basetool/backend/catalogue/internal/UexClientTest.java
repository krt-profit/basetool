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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.backend.catalogue.api.CatalogueMetricNames;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexCommodityDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexCommodityPriceDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexItemDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexRefineryYieldDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexStarSystemDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexVehicleDto;
import de.greluc.krt.profit.basetool.backend.platform.api.RestClientConfig;
import de.greluc.krt.profit.basetool.backend.support.BoundProperties;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Unit tests for {@link UexClient} against a {@link MockWebServer}: parsed happy path, empty-list
 * fallback on server errors, and empty-list result for an empty body, with each endpoint URI
 * pinned.
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

    properties =
        BoundProperties.bind(UexProperties.class, Map.of("api-url", server.url("/").toString()));

    meterRegistry = new SimpleMeterRegistry();
    client =
        new UexClient(
            new RestClientConfig().restClientBuilder(ObservationRegistry.NOOP),
            properties,
            meterRegistry,
            new SyncRunProgress());
    client.initClient();
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  /**
   * A UEX fetch records the {@code http.client.requests} observation with its default key values
   * (REQ-OBS-009).
   *
   * @throws Exception if the client cannot be built
   */
  @Test
  void fetch_recordsTheHttpClientRequestsObservation() throws Exception {
    SimpleMeterRegistry observed = new SimpleMeterRegistry();
    ObservationRegistry observationRegistry = ObservationRegistry.create();
    observationRegistry
        .observationConfig()
        .observationHandler(new DefaultMeterObservationHandler(observed));
    UexClient observedClient =
        new UexClient(
            new RestClientConfig().restClientBuilder(observationRegistry),
            properties,
            meterRegistry,
            new SyncRunProgress());
    observedClient.initClient();
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"status\":\"ok\",\"data\":[]}"));

    observedClient.getCommodities();

    Timer timer =
        observed
            .find("http.client.requests")
            .tag("method", "GET")
            .tag("status", "200")
            .tag("outcome", "SUCCESS")
            .tag("client.name", server.getHostName())
            .timer();
    assertNotNull(timer, "the UEX fetch must be observed as http.client.requests");
    assertEquals(1, timer.count());
    assertNotNull(timer.getId().getTag("uri"), "the uri key value must stay on the metric");
  }

  /**
   * A body past {@link UexClient#MAX_RESPONSE_BYTES} fails into the counted empty-result fallback
   * instead of a partial parse.
   */
  @Test
  void getCommodities_bodyPastTheSizeCap_failsIntoTheCountedFallback() {
    String filler = "x".repeat((int) UexClient.MAX_RESPONSE_BYTES);
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"status\":\"ok\",\"data\":[{\"id\":1,\"name\":\"" + filler + "\"}]}"));

    List<UexCommodityDto> commodities = client.getCommodities().data();

    assertTrue(commodities.isEmpty(), "an oversized body must not yield a partial parse");
    assertEquals(
        1.0,
        meterRegistry
            .get(CatalogueMetricNames.EXTERNAL_FETCH_ERRORS)
            .tag(CatalogueMetricNames.TAG_SOURCE, CatalogueMetricNames.SOURCE_UEX)
            .counter()
            .count());
  }

  @Test
  void getCommodities_happyPath_returnsParsedList() throws Exception {
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

    List<UexCommodityDto> commodities = client.getCommodities().data();

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
    server.enqueue(new MockResponse().setResponseCode(500).setBody("upstream exploded"));

    List<UexCommodityDto> commodities = client.getCommodities().data();

    assertNotNull(commodities, "fallback must return empty list, not null");
    assertTrue(commodities.isEmpty());
    assertEquals(
        1.0,
        meterRegistry
            .get(CatalogueMetricNames.EXTERNAL_FETCH_ERRORS)
            .tag(CatalogueMetricNames.TAG_SOURCE, CatalogueMetricNames.SOURCE_UEX)
            .counter()
            .count(),
        "a swallowed UEX fetch error must increment"
            + " basetool_external_fetch_errors_total{source=uex}");
  }

  @Test
  void getCommodities_connectionDropped_returnsEmptyList() {
    server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_REQUEST_BODY));

    List<UexCommodityDto> commodities = client.getCommodities().data();

    assertNotNull(commodities);
    assertTrue(commodities.isEmpty());
  }

  @Test
  void getCommodities_emptyDataArray_returnsEmptyList() {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", "application/json")
            .setBody("{\"status\":\"ok\",\"data\":[]}"));

    List<UexCommodityDto> commodities = client.getCommodities().data();

    assertNotNull(commodities);
    assertTrue(commodities.isEmpty());
  }

  @Test
  void getCommodities_nullDataEnvelope_returnsEmptyListWithoutLoggingAnError() {
    Logger uexLog = (Logger) LoggerFactory.getLogger(UexClient.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    uexLog.addAppender(appender);
    try {
      server.enqueue(jsonOk("{\"status\":\"ok\",\"data\":null}"));

      List<UexCommodityDto> commodities = client.getCommodities().data();

      assertNotNull(commodities, "null data must surface as an empty list, not null");
      assertTrue(commodities.isEmpty());
      assertTrue(
          appender.list.stream().noneMatch(e -> e.getLevel() == Level.ERROR),
          "null data is not a fetch failure — it must not be logged as ERROR");
    } finally {
      uexLog.detachAppender(appender);
    }
  }

  @Test
  void getCommoditiesPricesAll_happyPath_returnsParsedList() throws Exception {
    server.enqueue(
        jsonOk(
            """
            {"status":"ok","data":[
              {"id_commodity": 1, "price_buy": 12.5, "price_sell": 15.0}
            ]}
            """));

    List<UexCommodityPriceDto> prices = client.getCommoditiesPricesAll().data();

    assertEquals(1, prices.size());
    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("/commodities_prices_all", req.getPath());
  }

  @Test
  void getCommoditiesPricesAll_serverError_returnsEmptyList() {
    server.enqueue(new MockResponse().setResponseCode(503));
    assertTrue(client.getCommoditiesPricesAll().data().isEmpty());
  }

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

    List<UexStarSystemDto> systems = client.getStarSystems().data();

    assertEquals(2, systems.size());
    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("/star_systems", req.getPath());
  }

  @Test
  void getStarSystems_clientError_returnsEmptyList() {
    server.enqueue(new MockResponse().setResponseCode(404));
    assertTrue(client.getStarSystems().data().isEmpty());
  }

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

  @Test
  void getRefineriesYields_bindsTheWeekAndMonthAverages() {
    server.enqueue(
        jsonOk(
            """
            {"status":"ok","data":[
              {"id": 7, "id_commodity": 1, "id_terminal": 42,
               "value": 5, "value_week": 4, "value_month": -2},
              {"id": 8, "id_commodity": 2, "id_terminal": 42, "value": 3}
            ]}
            """));

    List<UexRefineryYieldDto> yields = client.getRefineriesYields();

    assertEquals(new UexRefineryYieldDto(7, 1, 42, 5, 4, -2), yields.get(0));
    assertEquals(new UexRefineryYieldDto(8, 2, 42, 3, null, null), yields.get(1));
  }

  @Test
  void repeatedCalls_sendNoConditionalHeader_evenWhenTheResponseCarriesAnEtag() throws Exception {
    server.enqueue(jsonOk("{\"status\":\"ok\",\"data\":[]}").setHeader("ETag", "\"abc-123\""));
    server.enqueue(jsonOk("{\"status\":\"ok\",\"data\":[]}"));

    client.getCommodities();
    client.getCommodities();

    server.takeRequest(1, TimeUnit.SECONDS);
    RecordedRequest secondReq = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(secondReq);
    assertNull(
        secondReq.getHeader("If-None-Match"),
        "UEX serves no ETag, so the client never sends a conditional request");
  }

  @Test
  void getVehicles_keepsAFractionalCargoCapacity() {
    server.enqueue(
        jsonOk("{\"status\":\"ok\",\"data\":[{\"id\":1,\"name\":\"ATLS GEO\",\"scu\":0.8}]}"));

    List<UexVehicleDto> rows = client.getVehicles().data();

    assertEquals(new BigDecimal("0.8"), rows.get(0).scu(), "0.8 SCU must not truncate to 0");
  }

  @Test
  void getItemsForCategory_freshResponse_returnsCompleteData() throws Exception {
    server.enqueue(jsonOk("{\"status\":\"ok\",\"data\":[{\"id\":42,\"name\":\"Helmet\"}]}"));

    UexClient.FetchResult<UexItemDto> result = client.getItemsForCategory(3);

    assertTrue(result.complete(), "a healthy 200 is a complete answer");
    assertEquals(1, result.data().size());
    assertEquals(42, result.data().get(0).id());

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    assertEquals("/items?id_category=3", req.getPath());
  }

  @Test
  void getItemsForCategory_empty200_returnsEmptyCompleteData() {
    server.enqueue(jsonOk("{\"status\":\"ok\",\"data\":[]}"));

    UexClient.FetchResult<UexItemDto> result = client.getItemsForCategory(7);

    assertTrue(result.complete(), "an empty 200 is a usable answer");
    assertTrue(result.data().isEmpty());
  }

  @Test
  void healthy200_logsCompletionInfoWithRowCountAndEnvelopeStatus() {
    String body = "{\"status\":\"ok\",\"data\":[{\"id\":1,\"name\":\"Gold\"}]}";
    List<ILoggingEvent> events =
        captureUexLog(() -> server.enqueue(jsonOk(body)), client::getCommodities);

    ILoggingEvent completion =
        events.stream()
            .filter(e -> e.getFormattedMessage().startsWith("Fetched 1 commodities"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no completion line: " + messages(events)));
    assertEquals(Level.INFO, completion.getLevel(), "a healthy fetch completes at INFO");
    assertTrue(
        completion.getFormattedMessage().contains("'ok'"),
        "the completion line must report the envelope status it read: "
            + completion.getFormattedMessage());
  }

  @Test
  void nullDataUnderOkStatus_isAnEmptyResultSetAndDoesNotCount() {
    List<ILoggingEvent> events =
        captureUexLog(
            () -> server.enqueue(jsonOk("{\"status\":\"ok\",\"data\":null,\"message\":\"\"}")),
            client::getCommodities);

    assertTrue(
        events.stream().noneMatch(e -> e.getLevel() == Level.WARN),
        "an empty result set is healthy and must not WARN: " + messages(events));
    assertEquals(
        0.0,
        fetchErrorCount(),
        "an empty result set must NOT increment"
            + " basetool_external_fetch_errors_total{source=uex}");

    server.enqueue(jsonOk("{\"status\":\"ok\",\"data\":null,\"message\":\"\"}"));
    UexClient.FetchResult<UexCommodityDto> result = client.getCommodities();
    assertTrue(result.data().isEmpty(), "absent data still reads as zero rows");
    assertTrue(result.complete(), "an empty result set is a usable answer");
  }

  @Test
  void nullDataUnderNonOkStatus_stillWarnsAndCounts() {
    List<ILoggingEvent> events =
        captureUexLog(
            () -> server.enqueue(jsonOk("{\"status\":\"error\",\"data\":null}")),
            client::getCommodities);

    assertTrue(
        events.stream()
            .anyMatch(e ->
                e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("envelope status 'error'")),
        "a self-declared upstream failure must WARN even with no data: " + messages(events));
    assertEquals(
        1.0, fetchErrorCount(), "a non-ok envelope status counts regardless of the data field");
  }

  @Test
  void nonOkEnvelopeStatus_warnsAndCounts_butStillReturnsTheRows() {
    String body = "{\"status\":\"error\",\"data\":[{\"id\":1,\"name\":\"Gold\"}]}";
    List<ILoggingEvent> events =
        captureUexLog(() -> server.enqueue(jsonOk(body)), client::getCommodities);

    assertTrue(
        events.stream()
            .anyMatch(e ->
                e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("envelope status 'error'")),
        "a self-declared upstream failure must WARN: " + messages(events));
    assertEquals(1.0, fetchErrorCount(), "a non-ok envelope status must count as a fetch error");

    server.enqueue(jsonOk(body));
    assertEquals(
        1,
        client.getCommodities().data().size(),
        "the client stays fail-soft: the rows are still handed to the caller");
  }

  @Test
  void blankEnvelopeStatus_isNotTreatedAsAnAnomaly() {
    List<ILoggingEvent> events =
        captureUexLog(
            () -> server.enqueue(jsonOk("{\"data\":[{\"id\":1,\"name\":\"Gold\"}]}")),
            client::getCommodities);

    assertTrue(
        events.stream().noneMatch(e -> e.getLevel() == Level.WARN),
        "an absent status must not warn: " + messages(events));
    assertEquals(0.0, fetchErrorCount(), "an absent status is not a fetch error");
  }

  /**
   * Runs {@code arrange} then {@code call} with a {@link ListAppender} attached to the {@link
   * UexClient} logger and returns everything it logged.
   *
   * @param arrange enqueues the responses (and any priming calls) the scenario needs
   * @param call the client call under test
   * @return the log events the client emitted during {@code arrange} and {@code call}
   */
  private List<ILoggingEvent> captureUexLog(Runnable arrange, Runnable call) {
    Logger uexLog = (Logger) LoggerFactory.getLogger(UexClient.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    uexLog.addAppender(appender);
    try {
      arrange.run();
      call.run();
      return List.copyOf(appender.list);
    } finally {
      uexLog.detachAppender(appender);
    }
  }

  /**
   * Reads the current {@code basetool_external_fetch_errors_total{source=uex}} value, tolerating an
   * unregistered counter (nothing failed yet) as {@code 0}.
   *
   * @return the counter value, or {@code 0} when the counter was never touched
   */
  private double fetchErrorCount() {
    return meterRegistry
        .find(CatalogueMetricNames.EXTERNAL_FETCH_ERRORS)
        .tag(CatalogueMetricNames.TAG_SOURCE, CatalogueMetricNames.SOURCE_UEX)
        .counters()
        .stream()
        .mapToDouble(io.micrometer.core.instrument.Counter::count)
        .sum();
  }

  /**
   * Renders captured log events for an assertion failure message.
   *
   * @param events the captured events
   * @return one {@code LEVEL message} line per event
   */
  private static String messages(List<ILoggingEvent> events) {
    return events.stream()
        .map(e -> e.getLevel() + " " + e.getFormattedMessage())
        .reduce("", (a, b) -> a + "\n" + b);
  }

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

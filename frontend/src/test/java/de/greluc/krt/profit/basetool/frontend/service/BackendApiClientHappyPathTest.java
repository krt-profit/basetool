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

import static org.junit.jupiter.api.Assertions.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.support.CatalogPages;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Happy-path coverage for every HTTP verb and overload on {@link BackendApiClient}, driven by
 * {@link MockWebServer}.
 *
 * <p>The companion suite {@code BackendApiClientResilienceTest} covers the error / unwrap branches
 * via plain Mockito; {@code BackendApiClientProblemJsonTest} covers RFC 7807 decoding. This file
 * plugs the remaining gap: the success path of every public method (cached / uncached, {@code
 * Class<T>} / {@code ParameterizedTypeReference<T>}, public / authenticated client, POST/PUT/PATCH
 * with and without a body, DELETE).
 *
 * <p>A single shared {@link MockWebServer} is used; the {@code isPublic} flag selects between two
 * WebClient instances that both point at the same server so we can observe which path was taken
 * (the server records every request).
 */
class BackendApiClientHappyPathTest {

  private MockWebServer server;
  private WebClient webClient;
  private WebClient termsDocumentClient;
  private BackendApiClient client;

  @BeforeEach
  void setUp() throws Exception {
    server = new MockWebServer();
    server.start();
    webClient =
        WebClient.builder()
            .baseUrl(server.url("/").toString())
            .defaultHeader("X-Auth", "authenticated")
            .build();
    termsDocumentClient =
        WebClient.builder()
            .baseUrl(server.url("/").toString())
            .defaultHeader("X-Auth", "public")
            .build();
    client =
        new BackendApiClient(
            webClient,
            termsDocumentClient,
            new SimpleMeterRegistry(),
            new org.springframework.cache.support.NoOpCacheManager());
  }

  @AfterEach
  void tearDown() throws Exception {
    server.shutdown();
  }

  // ── GET ─────────────────────────────────────────────────────────────────

  @Test
  void get_withClassResponseType_parsesBody() {
    // WebClient's String.class binding is plain-text (not JSON-deserialised),
    // so the body comes through verbatim.
    server.enqueue(jsonOk("hello"));

    String result = client.get("/api/v1/greeting", String.class);

    assertEquals("hello", result);
  }

  @Test
  void get_withClassResponseType_usesAuthenticatedClient_byDefault() throws Exception {
    server.enqueue(jsonOk("ok"));

    client.get("/api/v1/x", String.class);

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    assertEquals(
        "authenticated",
        req.getHeader("X-Auth"),
        "Default get(uri, Class) must use the authenticated WebClient");
  }

  @Test
  void get_withClassResponseType_andIsPublicTrue_usesPublicClient() throws Exception {
    server.enqueue(jsonOk("ok"));

    client.get("/api/v1/x", String.class);

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    assertEquals(
        "public",
        req.getHeader("X-Auth"),
        "get(uri, Class, isPublic=true) must use the public WebClient");
  }

  @Test
  void get_withParameterizedType_parsesList() {
    server.enqueue(jsonOk("[\"a\",\"b\",\"c\"]"));

    List<String> result = client.get("/api/v1/list", new ParameterizedTypeReference<>() {});

    assertEquals(List.of("a", "b", "c"), result);
  }

  @Test
  void get_withParameterizedType_andIsPublicTrue_usesPublicClient() throws Exception {
    server.enqueue(jsonOk("[]"));

    client.get("/api/v1/list", new ParameterizedTypeReference<List<String>>() {});

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("public", req.getHeader("X-Auth"));
  }

  @Test
  void get_withUriVariables_percentEncodesSpacesAndQuotes_notFormEncoding() throws Exception {
    // #371 regression guard: the blueprint owner drill-down passes a normalized product key
    // (spaces plus a literal quote) as a query value. The WebClient must percent-encode it
    // (space -> %20, '"' -> %22), never form-encode it (space -> '+'), so the backend
    // @RequestParam decodes the exact stored key. The previous URLEncoder.encode path produced
    // '+' for spaces and was mangled across the frontend->backend hop, yielding zero owners.
    server.enqueue(jsonOk("[]"));

    client.get(
        "/api/v1/personal-blueprints/overview/owners?productKey={productKey}",
        new ParameterizedTypeReference<List<String>>() {},
        "killshot \"dominion camo\" rifle");

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertNotNull(req);
    assertEquals(
        "/api/v1/personal-blueprints/overview/owners?productKey=killshot%20%22dominion%20camo%22%20rifle",
        req.getPath());
    assertEquals(
        "authenticated",
        req.getHeader("X-Auth"),
        "URI-variable GET must use the authenticated WebClient");
  }

  // ── getCached ───────────────────────────────────────────────────────────
  // The @Cacheable annotation is a no-op outside a Spring application
  // context, so the body executes normally — that's all we need to cover.
  // Plain (single-fetch) paths use ORG_UNITS_ACTIVE, a Fetch.SINGLE catalogue;
  // page-walked catalogues are covered by the dedicated section below.

  @Test
  void getCached_withClassResponseType_parsesBody() {
    server.enqueue(jsonOk("cached"));

    String result = client.getCached(CachedCatalog.ORG_UNITS_ACTIVE, String.class);

    assertEquals("cached", result);
  }

  @Test
  void getCached_withClassResponseType_andIsPublicTrue_usesPublicClient() throws Exception {
    server.enqueue(jsonOk("ok"));

    client.getCached(CachedCatalog.ORG_UNITS_ACTIVE, String.class);

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("public", req.getHeader("X-Auth"));
  }

  @Test
  void getCached_withParameterizedType_parsesBody() {
    server.enqueue(jsonOk("[1,2,3]"));

    List<Integer> result =
        client.getCached(CachedCatalog.ORG_UNITS_ACTIVE, new ParameterizedTypeReference<>() {});

    assertEquals(List.of(1, 2, 3), result);
  }

  @Test
  void getCached_withParameterizedType_andIsPublicTrue_usesPublicClient() throws Exception {
    server.enqueue(jsonOk("[]"));

    client.getCached(
        CachedCatalog.ORG_UNITS_ACTIVE, new ParameterizedTypeReference<List<Integer>>() {});

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("public", req.getHeader("X-Auth"));
  }

  // ── getCached page walk (REQ-ADMIN-003) ─────────────────────────────────

  @Test
  void getCached_pageWalkedCatalog_mergesAllPagesInOrder() throws Exception {
    server.enqueue(jsonOk(pageJson(List.of("a", "b"), 0, 2, 3, 2)));
    server.enqueue(jsonOk(pageJson(List.of("c"), 1, 2, 3, 2)));

    PageResponse<String> result =
        client.getCached(
            CachedCatalog.SQUADRONS, new ParameterizedTypeReference<PageResponse<String>>() {});

    assertEquals(List.of("a", "b", "c"), result.content());
    assertEquals(3L, result.totalElements(), "the merged envelope keeps the backend total");
    assertEquals(2, server.getRequestCount());
    RecordedRequest first = server.takeRequest(1, TimeUnit.SECONDS);
    RecordedRequest second = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("/api/v1/squadrons?size=1000&sort=name,asc&page=0", first.getPath());
    assertEquals("/api/v1/squadrons?size=1000&sort=name,asc&page=1", second.getPath());
  }

  @Test
  void getCached_pageWalkedCatalog_singlePage_makesExactlyOneRequest() {
    server.enqueue(jsonOk(pageJson(List.of("only"), 0, 1000, 1, 1)));

    PageResponse<String> result =
        client.getCached(
            CachedCatalog.SQUADRONS, new ParameterizedTypeReference<PageResponse<String>>() {});

    assertEquals(List.of("only"), result.content());
    assertEquals(1, server.getRequestCount(), "a one-chunk catalogue must cost one request");
  }

  @Test
  void getCached_pageWalkedCatalog_andIsPublicTrue_usesPublicClient() throws Exception {
    server.enqueue(jsonOk(pageJson(List.of("x"), 0, 1000, 1, 1)));

    client.getCached(
        CachedCatalog.SQUADRONS, new ParameterizedTypeReference<PageResponse<String>>() {});

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("public", req.getHeader("X-Auth"));
  }

  @Test
  void getCached_pageWalkedCatalog_classOverload_isRejectedWithoutAnyRequest() {
    IllegalArgumentException ex =
        assertThrows(
            IllegalArgumentException.class,
            () -> client.getCached(CachedCatalog.SQUADRONS, String.class));

    assertTrue(ex.getMessage().contains("SQUADRONS"));
    assertEquals(0, server.getRequestCount(), "the guard must reject before any backend call");
  }

  @Test
  void getCached_pageWalkedCatalog_capHit_stopsAndLogsWarning() {
    // A backend reporting more pages than the safety cap: the walk must stop at
    // MAX_CATALOG_PAGES and say so in the log (there is no banner surface for cached pulls).
    for (int i = 0; i < CatalogPages.MAX_CATALOG_PAGES; i++) {
      server.enqueue(jsonOk(pageJson(List.of("row" + i), i, 1, 1000, 1000)));
    }
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(BackendApiClient.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      PageResponse<String> result =
          client.getCached(
              CachedCatalog.SQUADRONS, new ParameterizedTypeReference<PageResponse<String>>() {});

      assertEquals(CatalogPages.MAX_CATALOG_PAGES, result.content().size());
      assertEquals(1000L, result.totalElements(), "the total must stay the backend's truth");
      assertEquals(CatalogPages.MAX_CATALOG_PAGES, server.getRequestCount());
      assertTrue(
          appender.list.stream()
              .anyMatch(
                  event ->
                      event.getLevel() == Level.WARN
                          && event.getFormattedMessage().contains("safety cap")),
          "hitting the cap must be loud in the log");
    } finally {
      logger.detachAppender(appender);
    }
  }

  /**
   * Builds the JSON of one backend {@code PageResponse} chunk for the page-walk tests.
   *
   * @param content the page's rows (JSON-encoded as plain strings)
   * @param page the zero-based page index the backend reports
   * @param size the page size the backend reports
   * @param totalElements the total row count the backend reports
   * @param totalPages the total page count the backend reports (drives the walk's continuation)
   * @return the serialised page JSON
   */
  private static String pageJson(
      List<String> content, int page, int size, long totalElements, int totalPages) {
    String rows =
        content.stream()
            .map(s -> "\"" + s + "\"")
            .collect(java.util.stream.Collectors.joining(","));
    return "{\"content\":["
        + rows
        + "],\"page\":"
        + page
        + ",\"size\":"
        + size
        + ",\"totalElements\":"
        + totalElements
        + ",\"totalPages\":"
        + totalPages
        + ",\"sort\":[]}";
  }

  @Test
  void clearStaticDataCache_doesNotThrow() {
    // @CacheEvict is a no-op without Spring context; method body only logs.
    // The smallest reachable assertion: it doesn't blow up.
    assertDoesNotThrow(() -> client.clearStaticDataCache());
  }

  // ── POST ────────────────────────────────────────────────────────────────

  @Test
  void post_withBody_sendsBodyAndParsesResponse() throws Exception {
    server.enqueue(jsonOk("{\"echo\":\"ok\"}"));

    String result = client.post("/api/v1/things", "{\"name\":\"x\"}", String.class);

    assertEquals("{\"echo\":\"ok\"}", result);
    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("POST", req.getMethod());
    assertEquals("/api/v1/things", req.getPath());
    assertTrue(req.getBody().readUtf8().contains("\"name\""));
  }

  @Test
  void post_withNullBody_sendsEmptyBody() throws Exception {
    // The {@code body != null} branch in executePost is otherwise uncovered.
    server.enqueue(jsonOk("ok"));

    String result = client.post("/api/v1/trigger", null, String.class);

    assertEquals("ok", result);
    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("POST", req.getMethod());
    assertEquals(0L, req.getBodySize(), "null body must not be serialised");
  }

  @Test
  void post_withIsPublicTrue_usesPublicClient() throws Exception {
    server.enqueue(jsonOk("ok"));

    client.post("/api/v1/pub", "body", String.class);

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("public", req.getHeader("X-Auth"));
    assertEquals("POST", req.getMethod());
  }

  // ── PUT ─────────────────────────────────────────────────────────────────

  @Test
  void put_withBody_sendsBodyAndParsesResponse() throws Exception {
    server.enqueue(jsonOk("replaced"));

    String result = client.put("/api/v1/things/1", "{\"a\":1}", String.class);

    assertEquals("replaced", result);
    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("PUT", req.getMethod());
    assertEquals("/api/v1/things/1", req.getPath());
  }

  @Test
  void put_withNullBody_sendsEmptyBody() throws Exception {
    server.enqueue(jsonOk("ok"));

    String result = client.put("/api/v1/things/1", null, String.class);

    assertEquals("ok", result);
    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals(0L, req.getBodySize());
  }

  @Test
  void put_withIsPublicTrue_usesPublicClient() throws Exception {
    server.enqueue(jsonOk("ok"));

    client.put("/api/v1/things/1", "x", String.class);

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("public", req.getHeader("X-Auth"));
    assertEquals("PUT", req.getMethod());
  }

  // ── PATCH ───────────────────────────────────────────────────────────────

  @Test
  void patch_withBody_sendsBodyAndParsesResponse() throws Exception {
    server.enqueue(jsonOk("patched"));

    String result = client.patch("/api/v1/things/1", "{\"a\":1}", String.class);

    assertEquals("patched", result);
    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("PATCH", req.getMethod());
  }

  @Test
  void patch_withNullBody_sendsEmptyBody() throws Exception {
    server.enqueue(jsonOk("ok"));

    String result = client.patch("/api/v1/things/1", null, String.class);

    assertEquals("ok", result);
    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals(0L, req.getBodySize());
  }

  @Test
  void patch_withIsPublicTrue_usesPublicClient() throws Exception {
    server.enqueue(jsonOk("ok"));

    client.patch("/api/v1/things/1", "x", String.class);

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("public", req.getHeader("X-Auth"));
    assertEquals("PATCH", req.getMethod());
  }

  // ── DELETE ──────────────────────────────────────────────────────────────

  @Test
  void delete_returnsParsedResponse() throws Exception {
    server.enqueue(jsonOk("deleted"));

    String result = client.delete("/api/v1/things/1", String.class);

    assertEquals("deleted", result);
    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("DELETE", req.getMethod());
    assertEquals("/api/v1/things/1", req.getPath());
  }

  @Test
  void delete_withIsPublicTrue_usesPublicClient() throws Exception {
    server.enqueue(jsonOk("ok"));

    client.delete("/api/v1/things/1", String.class);

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("public", req.getHeader("X-Auth"));
    assertEquals("DELETE", req.getMethod());
  }

  @Test
  void delete_void_returnsNull() throws Exception {
    // 204 No Content scenario — frontend code uses Void.class
    server.enqueue(new MockResponse().setResponseCode(204));

    Void result = client.delete("/api/v1/things/1", Void.class);

    assertNull(result);
    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("DELETE", req.getMethod());
  }

  // ── helpers ─────────────────────────────────────────────────────────────

  private static MockResponse jsonOk(String body) {
    return new MockResponse()
        .setResponseCode(200)
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }
}

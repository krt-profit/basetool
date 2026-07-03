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
  private WebClient publicWebClient;
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
    publicWebClient =
        WebClient.builder()
            .baseUrl(server.url("/").toString())
            .defaultHeader("X-Auth", "public")
            .build();
    client = new BackendApiClient(webClient, publicWebClient, new SimpleMeterRegistry());
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

    client.get("/api/v1/x", String.class, true);

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

    client.get("/api/v1/list", new ParameterizedTypeReference<List<String>>() {}, true);

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

  @Test
  void getCached_withClassResponseType_parsesBody() {
    server.enqueue(jsonOk("cached"));

    String result = client.getCached("/api/v1/cached", String.class);

    assertEquals("cached", result);
  }

  @Test
  void getCached_withClassResponseType_andIsPublicTrue_usesPublicClient() throws Exception {
    server.enqueue(jsonOk("ok"));

    client.getCached("/api/v1/cached", String.class, true);

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("public", req.getHeader("X-Auth"));
  }

  @Test
  void getCached_withParameterizedType_parsesBody() {
    server.enqueue(jsonOk("[1,2,3]"));

    List<Integer> result =
        client.getCached("/api/v1/cached-list", new ParameterizedTypeReference<>() {});

    assertEquals(List.of(1, 2, 3), result);
  }

  @Test
  void getCached_withParameterizedType_andIsPublicTrue_usesPublicClient() throws Exception {
    server.enqueue(jsonOk("[]"));

    client.getCached(
        "/api/v1/cached-list", new ParameterizedTypeReference<List<Integer>>() {}, true);

    RecordedRequest req = server.takeRequest(1, TimeUnit.SECONDS);
    assertEquals("public", req.getHeader("X-Auth"));
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

    client.post("/api/v1/pub", "body", String.class, true);

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

    client.put("/api/v1/things/1", "x", String.class, true);

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

    client.patch("/api/v1/things/1", "x", String.class, true);

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

    client.delete("/api/v1/things/1", String.class, true);

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

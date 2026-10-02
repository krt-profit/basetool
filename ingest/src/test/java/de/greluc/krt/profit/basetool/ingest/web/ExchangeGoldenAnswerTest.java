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

package de.greluc.krt.profit.basetool.ingest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;

import com.nimbusds.jose.jwk.ECKey;
import de.greluc.krt.profit.basetool.ingest.handoff.HandoffKind;
import de.greluc.krt.profit.basetool.ingest.handoff.HandoffStagingService;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistryReader;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRevocationReader;
import de.greluc.krt.profit.basetool.ingest.relay.ServiceAccountTokenProvider;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeBudget;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeIdempotency;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeQuotas;
import de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport;
import de.greluc.krt.profit.basetool.testsupport.exchange.ExchangeSeam;
import jakarta.servlet.Filter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.boot.web.servlet.ServletContextInitializerBeans;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records the gateway's answer on every {@code /exchange/v1} route under a fixed backend stub and
 * compares it byte for byte with the committed golden answer (REQ-XCH-036): the client's request,
 * what the relay sent to the backend with every header, and the status, every header and the body
 * the client got back.
 *
 * <p>Only these volatile values are normalised, each to a named placeholder: the DPoP key's
 * thumbprint, the token's connection time, the correlation id, the DPoP nonce, the idempotency key
 * and the trace context. The backend stub answers with the published fixtures. The goldens are the
 * baseline a re-package of the gateway must keep; on a difference the observed answer is written
 * under {@code build/exchange-golden/} for review.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ExchangeGoldenAnswerTest {

  private static final Path GOLDEN = Path.of("src/test/resources/exchange/golden");

  private static final Path OBSERVED = Path.of("build/exchange-golden");

  private static final Path EXAMPLES = Path.of("../docs/exchange/examples/v1");

  private static final String TOKEN = "golden-token";

  private static final String GATEWAY_TOKEN = "gateway-service-token";

  private static final String MEMBER = "6f1c2e7a-3b4d-4c5e-8f60-718293a4b5c6";

  private static final String USER_AGENT = "VerseKit/2.1.0";

  private static final Set<String> SCOPES = ExchangeSeam.CAPABILITY_SCOPES;

  /** Relayed request headers left out of a golden, each with the reason. */
  private static final Map<String, String> UNRECORDED_RELAY_HEADERS =
      Map.of(
          "host", "the stub's random port",
          "user-agent", "the JDK's HTTP client version",
          "connection", "transport detail of the JDK's HTTP client",
          "upgrade", "transport detail of the JDK's HTTP client",
          "http2-settings", "transport detail of the JDK's HTTP client");

  private static final String PREVIEW =
      "{\"total\":1,\"matched\":1,\"matchedByAlias\":0,\"suggested\":0,\"unmatched\":0,"
          + "\"alreadyOwned\":0,\"entries\":[]}";

  private static final String REFINERY_DRAFT =
      "{\"ownerId\":\"" + MEMBER + "\",\"goods\":[],\"issues\":[]}";

  private static final String SHIP_PAGE =
      "{\"items\":[{\"shipId\":\"0b9f6c1e-2a3d-4e5f-8a7b-9c0d1e2f3a4b\",\"version\":0,"
          + "\"shipType\":{\"bt\":\"7c8d9e0f-1a2b-4c3d-8e4f-5a6b7c8d9e0f\","
          + "\"name\":\"Cutlass Black\"},\"insurance\":{\"kind\":\"LTI\"},\"fitted\":false}],"
          + "\"removed\":[],\"nextCursor\":\"f1.9.0\",\"hasMore\":false}";

  private static MockWebServer backend;

  private static final Map<String, MockResponse> OVERRIDES = new ConcurrentHashMap<>();

  private static final List<RecordedRequest> RELAYED = new ArrayList<>();

  private static final Set<String> RELAYED_OPERATIONS = new TreeSet<>();

  @Autowired private WebApplicationContext context;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private HandoffStagingService stagingService;
  @MockitoBean private ExchangeRegistryReader registryReader;
  @MockitoBean private ExchangeRevocationReader revocationReader;
  @MockitoBean private ExchangeQuotas quotas;
  @MockitoBean private ExchangeIdempotency idempotency;
  @MockitoBean private ExchangeBudget budget;
  @MockitoBean private ServiceAccountTokenProvider tokenProvider;

  private MockMvc mockMvc;
  private ECKey key;
  private String thumbprint;
  private Instant issuedAt;

  @BeforeAll
  static void startBackend() throws IOException {
    backend = new MockWebServer();
    backend.setDispatcher(
        new Dispatcher() {
          @Override
          public @NotNull MockResponse dispatch(@NotNull RecordedRequest request) {
            String target = request.getMethod() + " " + pathOf(request);
            synchronized (RELAYED) {
              RELAYED.add(request);
              RELAYED_OPERATIONS.add(target);
            }
            MockResponse override = OVERRIDES.get(target);
            return override != null ? override : json(200, defaultAnswer(target));
          }
        });
    backend.start();
  }

  @AfterAll
  static void stopBackend() throws IOException {
    backend.shutdown();
  }

  /**
   * Points the relay at the backend stub.
   *
   * @param registry the dynamic property registry of the test context
   */
  @DynamicPropertySource
  static void backendUrl(DynamicPropertyRegistry registry) {
    registry.add("app.ingest.backend-base-url", () -> backend.url("/").toString());
  }

  @BeforeEach
  void setUp() throws Exception {
    OVERRIDES.clear();
    synchronized (RELAYED) {
      RELAYED.clear();
      RELAYED_OPERATIONS.clear();
    }
    List<Filter> servletFilters = new ArrayList<>();
    for (ServletContextInitializer initializer : new ServletContextInitializerBeans(context)) {
      if (initializer instanceof FilterRegistrationBean<?> registration) {
        servletFilters.add(registration.getFilter());
      }
    }
    assertThat(servletFilters).as("the gateway's servlet filters").hasSizeGreaterThanOrEqualTo(5);
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(servletFilters.toArray(Filter[]::new))
            .apply(springSecurity())
            .build();
    key = ExchangeTestSupport.newKey();
    thumbprint = ExchangeTestSupport.thumbprint(key);
    issuedAt = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.SECONDS);
    when(jwtDecoder.decode(TOKEN))
        .thenReturn(
            ExchangeTestSupport.token(
                TOKEN,
                "basetool-ingest",
                thumbprint,
                MEMBER,
                String.join(" ", new TreeSet<>(SCOPES)),
                issuedAt));
    when(registryReader.current())
        .thenReturn(ExchangeTestSupport.registryWithLimits(SCOPES, "2.0.0", 1000));
    when(revocationReader.isDenied(anyString())).thenReturn(false);
    when(tokenProvider.currentToken()).thenReturn(GATEWAY_TOKEN);
    when(quotas.countWrite(anyString(), anyString()))
        .thenReturn(new ExchangeQuotas.Counted("ingest:xch:quota:golden", 1L));
    when(idempotency.claim(anyString())).thenReturn(Optional.of("claim-token"));
    when(budget.reserve(anyString(), anyString(), anyString(), anyLong(), any())).thenReturn(true);
    when(budget.settle(
            anyString(), anyString(), anyString(), anyLong(), anyString(), anyLong(), any()))
        .thenReturn(true);
    when(stagingService.stagedBytes(any(), anyString())).thenReturn(222L);
    when(stagingService.stageDraft(
            eq(ExchangeTestSupport.CLIENT), eq(MEMBER), any(), anyString(), anyInt()))
        .thenAnswer(
            invocation ->
                new HandoffStagingService.Staged(
                    "hid-" + invocation.getArgument(2, HandoffKind.class).name().toLowerCase(),
                    "ingest:handoff:golden",
                    222L));
    when(stagingService.stageMassChange(
            eq(ExchangeTestSupport.CLIENT), eq(MEMBER), anyString(), anyLong()))
        .thenReturn(new HandoffStagingService.Staged("hid-mass", "ingest:handoff:golden", 321L));
  }

  @Test
  void everyRouteAnswersAsRecordedAndRelaysExactlyTheSeamsOperations() throws Exception {
    List<String> mismatched = new ArrayList<>();
    for (Map.Entry<String, Call> entry : routes().entrySet()) {
      if (!matches(entry.getKey(), record(entry.getValue()))) {
        mismatched.add(entry.getKey());
      }
    }
    assertThat(mismatched).as("goldens that differ; see " + OBSERVED).isEmpty();
    assertRelayedOperations(ExchangeSeam.relayOperations());
  }

  @Test
  void aRelayOperationTheSeamDoesNotKnowIsCaught() throws Exception {
    for (Call call : routes().values()) {
      record(call);
    }
    Set<String> extra = new TreeSet<>(ExchangeSeam.relayOperations());
    extra.add("GET " + ExchangeSeam.RELAY_PREFIX + "/me/stock/history");
    assertThatThrownBy(() -> assertRelayedOperations(extra)).isInstanceOf(AssertionError.class);
  }

  @Test
  void aRelayedRefusalAndARelayFailureAnswerAsRecorded() throws Exception {
    List<String> mismatched = new ArrayList<>();
    OVERRIDES.put(
        "POST /api/v1/exchange/me/stock/changes", json(409, "{\"code\":\"OPTIMISTIC_LOCK\"}"));
    OVERRIDES.put("GET /api/v1/exchange/me/ships", json(403, "{\"code\":\"SCOPE_MISSING\"}"));
    OVERRIDES.put("GET /api/v1/exchange/me/org-demand", json(500, "{\"code\":\"INTERNAL\"}"));
    OVERRIDES.put("GET /api/v1/exchange/catalog/locations", json(200, "{\"items\":[{}]}"));
    OVERRIDES.put(
        "GET /api/v1/exchange/me/installation", json(503, "{\"code\":\"EXCHANGE_DISABLED\"}"));
    Map<String, Call> refusals = new LinkedHashMap<>();
    refusals.put("refused-stock-changes-version-conflict", post("/me/stock/changes", stockSet()));
    refusals.put("refused-ships-scope-missing", get("/me/ships"));
    refusals.put("failed-org-demand-backend-error", get("/me/org-demand"));
    refusals.put("failed-locations-answer-breaks-schema", get("/catalog/locations"));
    refusals.put("refused-service-document-exchange-disabled", get(""));
    for (Map.Entry<String, Call> entry : refusals.entrySet()) {
      if (!matches(entry.getKey(), record(entry.getValue()))) {
        mismatched.add(entry.getKey());
      }
    }
    assertThat(mismatched).as("goldens that differ; see " + OBSERVED).isEmpty();
  }

  @Test
  void aHeldBackMassChangeIsStagedWithTheSeamsMembersAndAnswersAsRecorded() throws Exception {
    OVERRIDES.put(
        "POST /api/v1/exchange/me/stock/changes",
        json(409, "{\"code\":\"MASS_CHANGE_CONFIRMATION_REQUIRED\"}"));

    assertThat(
            matches(
                "staged-stock-changes-mass-change", record(post("/me/stock/changes", stockSet()))))
        .as("golden differs; see " + OBSERVED)
        .isTrue();

    ArgumentCaptor<String> staged = ArgumentCaptor.forClass(String.class);
    verify(stagingService)
        .stageMassChange(eq(ExchangeTestSupport.CLIENT), eq(MEMBER), staged.capture(), anyLong());
    assertThat(
            new TreeSet<>(JsonMapper.builder().build().readTree(staged.getValue()).propertyNames()))
        .isEqualTo(new TreeSet<>(ExchangeSeam.MASS_CHANGE_FIELDS));
  }

  @Test
  void aChangedAnswerIsCaught() throws Exception {
    String observed = record(get("/me/stock"));
    String golden = golden("route-get-stock");
    assertThat(observed).isEqualTo(golden);
    String changed = observed.replace("\"quality\":1", "\"quality\":2");
    assertThat(changed).isNotEqualTo(observed);
    assertThatThrownBy(() -> assertThat(changed).isEqualTo(golden))
        .isInstanceOf(AssertionError.class);
  }

  /**
   * The calls of the default answers, one per route.
   *
   * @return the golden's name to the call
   * @throws IOException if a fixture cannot be read
   */
  private static @NotNull Map<String, Call> routes() throws IOException {
    Map<String, Call> routes = new LinkedHashMap<>();
    routes.put("route-get-service-document", get(""));
    routes.put(
        "route-post-installation",
        post("/me/installation", fixture("installation/valid/windows.json")));
    routes.put(
        "route-post-account-check",
        post("/me/account-check", fixture("account-check-request/valid/handle.json")));
    routes.put(
        "route-post-catalog-resolve",
        post(
            "/catalog/resolve",
            "{\"kind\":\"BLUEPRINT\",\"hint\":1,\"refs\":[{\"name\":\"Arrowhead\",\"colour\":\"red\"}]}"));
    routes.put("route-get-catalog-locations", get("/catalog/locations"));
    routes.put("route-get-blueprints", get("/me/blueprints?cursor=c-17&limit=50"));
    routes.put(
        "route-post-blueprint-changes",
        post(
            "/me/blueprints/changes",
            fixture("change-set--blueprintChangeSet/valid/add-and-remove.json")));
    routes.put("route-get-stock", get("/me/stock"));
    routes.put("route-post-stock-changes", post("/me/stock/changes", stockSet()));
    routes.put("route-get-ships", get("/me/ships?limit=10"));
    routes.put(
        "route-post-ship-changes",
        post(
            "/me/ships/changes", fixture("change-set--shipChangeSet/valid/link-then-upsert.json")));
    routes.put("route-get-org-demand", get("/me/org-demand"));
    routes.put(
        "route-post-blueprint-draft",
        post("/me/drafts/blueprints", fixture("blueprint-draft/valid/corpus-slice.json")));
    routes.put(
        "route-post-refinery-draft",
        post("/me/drafts/refinery-orders", fixture("refinery-draft/valid/minimal.json")));
    routes.put("route-get-openapi", get("/openapi.json"));
    routes.put("route-get-schema", get("/schemas/page.schema.json"));
    return routes;
  }

  /**
   * The backend stub's default answer per relay operation, the published fixture where one exists.
   *
   * @param target {@code METHOD path}
   * @return the answer's body
   */
  private static @NotNull String defaultAnswer(@NotNull String target) {
    try {
      return switch (target) {
        case "GET /api/v1/exchange/me/installation", "POST /api/v1/exchange/me/installation" ->
            fixture("installation/valid/response.json");
        case "POST /api/v1/exchange/me/account-check" ->
            fixture("account-check-response/valid/match.json");
        case "POST /api/v1/exchange/catalog/resolve" ->
            fixture("resolve-response/valid/mixed.json");
        case "GET /api/v1/exchange/catalog/locations" -> fixture("location-list/valid/two.json");
        case "GET /api/v1/exchange/me/blueprints" -> fixture("page--blueprintPage/valid/feed.json");
        case "GET /api/v1/exchange/me/stock" -> fixture("page--stockPage/valid/snapshot.json");
        case "GET /api/v1/exchange/me/ships" -> SHIP_PAGE;
        case "GET /api/v1/exchange/me/org-demand" -> fixture("org-demand/valid/both-lists.json");
        case "POST /api/v1/exchange/me/blueprints/changes",
            "POST /api/v1/exchange/me/stock/changes",
            "POST /api/v1/exchange/me/ships/changes" ->
            "{\"dryRun\":false,\"applied\":1,\"unchanged\":0,\"notApplied\":1,"
                + "\"results\":[{\"index\":1,\"result\":\"unmatched\"}],"
                + "\"offersReduced\":0,\"offersRemoved\":0}";
        case "POST /api/v1/exchange/me/drafts/blueprints" -> PREVIEW;
        case "POST /api/v1/exchange/me/drafts/refinery-orders" -> REFINERY_DRAFT;
        default -> "{\"code\":\"NOT_FOUND\"}";
      };
    } catch (IOException e) {
      throw new IllegalStateException("A published fixture is unreadable", e);
    }
  }

  /**
   * Sends one call through both gates and renders what the client sent, what the backend received
   * and what the client got back.
   *
   * @param call the call
   * @return the normalised rendering
   * @throws Exception if the request fails
   */
  private @NotNull String record(@NotNull Call call) throws Exception {
    synchronized (RELAYED) {
      RELAYED.clear();
    }
    String path = ExchangeSeam.GATEWAY_PREFIX + call.path();
    MockHttpServletResponse response =
        ExchangeTestSupport.call(mockMvc, key, TOKEN, call.method(), path, call.body(), USER_AGENT)
            .andReturn()
            .getResponse();
    StringBuilder out = new StringBuilder();
    out.append("=== request\n").append(call.method().name()).append(' ').append(path).append('\n');
    if (call.body() != null) {
      out.append(call.body().strip()).append('\n');
    }
    List<RecordedRequest> relayed;
    synchronized (RELAYED) {
      relayed = List.copyOf(RELAYED);
    }
    for (RecordedRequest request : relayed) {
      out.append("=== relayed\n")
          .append(request.getMethod())
          .append(' ')
          .append(request.getPath())
          .append('\n');
      Map<String, String> headers = new TreeMap<>();
      for (String name : request.getHeaders().names()) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (!UNRECORDED_RELAY_HEADERS.containsKey(lower)) {
          headers.put(lower, String.join(", ", request.getHeaders().values(name)));
        }
      }
      headers.forEach((name, value) -> out.append(name).append(": ").append(value).append('\n'));
      String body = request.getBody().readUtf8();
      if (!body.isEmpty()) {
        out.append(body).append('\n');
      }
    }
    out.append("=== response\n").append(response.getStatus()).append('\n');
    for (String name : new TreeSet<>(response.getHeaderNames())) {
      out.append(name.toLowerCase(Locale.ROOT))
          .append(": ")
          .append(String.join(", ", response.getHeaders(name)))
          .append('\n');
    }
    String body = response.getContentAsString(StandardCharsets.UTF_8);
    if (call.path().startsWith("/openapi.json") || call.path().startsWith("/schemas/")) {
      body =
          "sha-256 "
              + HexFormat.of()
                  .formatHex(
                      MessageDigest.getInstance("SHA-256")
                          .digest(body.getBytes(StandardCharsets.UTF_8)));
    }
    out.append(body).append('\n');
    return normalise(out.toString(), response);
  }

  /**
   * Replaces the volatile values of a rendering by named placeholders.
   *
   * @param rendering the rendering
   * @param response the client's answer, which carries the correlation id
   * @return the normalised rendering
   */
  private @NotNull String normalise(
      @NotNull String rendering, @NotNull MockHttpServletResponse response) {
    String out = rendering.replace(thumbprint, "<thumbprint>");
    out = out.replace(String.valueOf(issuedAt.getEpochSecond()), "<connected-at>");
    String correlationId = response.getHeader("X-Correlation-Id");
    if (correlationId != null && !correlationId.isBlank()) {
      out = out.replace(correlationId, "<correlation-id>");
    }
    String nonce = response.getHeader("DPoP-Nonce");
    if (nonce != null && !nonce.isBlank()) {
      out = out.replace(nonce, "<dpop-nonce>");
    }
    out = out.replaceAll("(?m)^(idempotency-key): test-[0-9a-f-]{36}$", "$1: <idempotency-key>");
    out = out.replaceAll("(?m)^(traceparent|tracestate|b3): .*$", "$1: <trace>");
    out =
        out.replaceAll(
            "(?m)^(ratelimit: limit=\\d+), remaining=\\d+, reset=\\d+$",
            "$1, remaining=<remaining>, reset=<reset>");
    return out;
  }

  /**
   * Compares a rendering with its committed golden, writing the rendering for review when they
   * differ.
   *
   * @param name the golden's name
   * @param observed the rendering
   * @return {@code true} when both are identical
   * @throws IOException if a file cannot be read or written
   */
  private static boolean matches(@NotNull String name, @NotNull String observed)
      throws IOException {
    if (observed.equals(golden(name))) {
      return true;
    }
    Files.createDirectories(OBSERVED);
    Files.writeString(OBSERVED.resolve(name + ".golden"), observed, StandardCharsets.UTF_8);
    return false;
  }

  /**
   * Reads a committed golden, its line endings normalised to {@code \n}.
   *
   * @param name the golden's name
   * @return its text, or {@code null} when it is not committed
   * @throws IOException if it cannot be read
   */
  private static @Nullable String golden(@NotNull String name) throws IOException {
    Path golden = GOLDEN.resolve(name + ".golden");
    return Files.exists(golden)
        ? Files.readString(golden, StandardCharsets.UTF_8).replace("\r\n", "\n")
        : null;
  }

  /**
   * Asserts that the backend stub received exactly the given operations.
   *
   * @param expected {@code METHOD /api/v1/exchange/...}
   */
  private static void assertRelayedOperations(@NotNull Set<String> expected) {
    Set<String> relayed;
    synchronized (RELAYED) {
      relayed = new TreeSet<>(RELAYED_OPERATIONS);
    }
    assertThat(relayed).isEqualTo(new TreeSet<>(expected));
  }

  /**
   * Returns a recorded request's path without its query.
   *
   * @param request the request the stub received
   * @return the path
   */
  private static @NotNull String pathOf(@NotNull RecordedRequest request) {
    String path = request.getPath() == null ? "" : request.getPath();
    int query = path.indexOf('?');
    return query < 0 ? path : path.substring(0, query);
  }

  /**
   * Builds a JSON answer of the stub.
   *
   * @param status the status
   * @param body the body
   * @return the answer
   */
  private static @NotNull MockResponse json(int status, @NotNull String body) {
    return new MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", status >= 400 ? "application/problem+json" : "application/json")
        .setBody(body);
  }

  /**
   * Reads a published fixture.
   *
   * @param relative the path below the fixture root
   * @return its text
   * @throws IOException if it cannot be read
   */
  private static @NotNull String fixture(@NotNull String relative) throws IOException {
    return Files.readString(EXAMPLES.resolve(relative), StandardCharsets.UTF_8);
  }

  /**
   * A stock change set that sets one lot.
   *
   * @return the change set
   * @throws IOException if the fixture cannot be read
   */
  private static @NotNull String stockSet() throws IOException {
    return fixture("change-set--stockChangeSet/valid/set.json");
  }

  /**
   * A read below the gateway prefix.
   *
   * @param path the path and query below {@code /exchange/v1}
   * @return the call
   */
  private static @NotNull Call get(@NotNull String path) {
    return new Call(HttpMethod.GET, path, null);
  }

  /**
   * A write below the gateway prefix.
   *
   * @param path the path below {@code /exchange/v1}
   * @param body the JSON body
   * @return the call
   */
  private static @NotNull Call post(@NotNull String path, @NotNull String body) {
    return new Call(HttpMethod.POST, path, body);
  }

  /**
   * One client call.
   *
   * @param method the method
   * @param path the path and query below {@code /exchange/v1}
   * @param body the JSON body, or {@code null}
   */
  private record Call(@NotNull HttpMethod method, @NotNull String path, @Nullable String body) {}
}

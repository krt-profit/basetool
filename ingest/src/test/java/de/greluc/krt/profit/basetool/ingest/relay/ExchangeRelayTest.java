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

package de.greluc.krt.profit.basetool.ingest.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import de.greluc.krt.profit.basetool.ingest.gate.ExchangeGateFilter;
import de.greluc.krt.profit.basetool.ingest.gate.ExchangeRequestContext;
import de.greluc.krt.profit.basetool.ingest.observability.ExchangeRefusals;
import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistry;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistryReader;
import de.greluc.krt.profit.basetool.ingest.support.TestLoggingProperties;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ExchangeRelayTest {

  private static final String RESULT =
      "{\"dryRun\":false,\"applied\":1,\"unchanged\":0,\"notApplied\":0,\"results\":[]}";

  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final JsonMapper mapper = JsonMapper.builder().build();
  private final BulkheadRegistry bulkheads =
      BulkheadRegistry.of(
          BulkheadConfig.custom().maxConcurrentCalls(1).maxWaitDuration(Duration.ZERO).build());
  private MockRestServiceServer backend;
  private ExchangeRelay relay;
  private ServiceAccountTokenProvider tokens;

  @BeforeEach
  void setUp() {
    RestClient.Builder builder = RestClient.builder().baseUrl("https://backend");
    backend = MockRestServiceServer.bindTo(builder).build();
    tokens = mock(ServiceAccountTokenProvider.class);
    when(tokens.currentToken()).thenReturn("gateway-token");
    relay = relay(builder.build(), tokens, CircuitBreakerRegistry.ofDefaults(), bulkheads);
    relay.register();
  }

  @Test
  void theRelayNamesTheGatewayMemberClientCapabilitiesAndKey() {
    backend
        .expect(requestTo("https://backend/api/v1/exchange/catalog/locations"))
        .andExpect(method(HttpMethod.GET))
        .andExpect(header("Authorization", "Bearer gateway-token"))
        .andExpect(header("X-Ingest-On-Behalf-Of", "member-1"))
        .andExpect(header(ExchangeRelay.CLIENT_HEADER, "versekit"))
        .andExpect(
            header(ExchangeRelay.CAPABILITIES_HEADER, "exchange.connect,exchange.stock.read"))
        .andExpect(header(ExchangeRelay.INSTALLATION_HEADER, "jkt-1"))
        .andExpect(header(ExchangeRelay.CONNECTED_AT_HEADER, "1790000000"))
        .andExpect(header("Accept-Language", "de-DE"))
        .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));

    ExchangeRelay.Result result =
        relay.forward(
            HttpMethod.GET, "/api/v1/exchange/catalog/locations", null, context(), "de-DE");

    backend.verify();
    assertThat(result.isOk()).isTrue();
    assertThat(result.body().get("items").isArray()).isTrue();
    assertThat(count("ok")).isEqualTo(1.0d);
  }

  @Test
  void aTokenWithoutIssuedAtIsRelayedWithoutTheHeader() {
    backend
        .expect(requestTo("https://backend/api/v1/exchange/catalog/locations"))
        .andExpect(headerDoesNotExist(ExchangeRelay.CONNECTED_AT_HEADER))
        .andRespond(withSuccess("{\"items\":[]}", MediaType.APPLICATION_JSON));
    ExchangeRequestContext admitted = context();
    ExchangeRequestContext withoutIssuedAt =
        new ExchangeRequestContext(
            admitted.clientId(),
            admitted.member(),
            admitted.keyThumbprint(),
            admitted.capabilities(),
            admitted.client(),
            null);

    relay.forward(
        HttpMethod.GET, "/api/v1/exchange/catalog/locations", null, withoutIssuedAt, null);

    backend.verify();
  }

  @Test
  void theBackendsGenericRefusalBecomesNotPermitted() {
    assertThat(interpret(403, "{\"code\":\"ACCESS_DENIED\",\"detail\":\"no\"}"))
        .satisfies(
            r -> {
              assertThat(r.status()).isEqualTo(403);
              assertThat(r.code()).isEqualTo("NOT_PERMITTED");
              assertThat(r.detail()).isEqualTo(ExchangeRelay.DETAILS.get("NOT_PERMITTED"));
            });
    assertThat(interpret(400, "{\"code\":\"VALIDATION_FAILED\"}").code())
        .isEqualTo("SCHEMA_INVALID");
    assertThat(interpret(400, "{\"code\":\"BAD_REQUEST\"}").code()).isEqualTo("SCHEMA_INVALID");
    assertThat(interpret(409, "{\"code\":\"OPTIMISTIC_LOCK\"}").code())
        .isEqualTo("VERSION_CONFLICT");
    assertThat(count("refused")).isEqualTo(4.0d);
  }

  @Test
  void aRegistryCodePassesThrough() {
    ExchangeRelay.Result result = interpret(403, "{\"code\":\"PENDING_APPROVAL\"}");

    assertThat(result.status()).isEqualTo(403);
    assertThat(result.code()).isEqualTo("PENDING_APPROVAL");
  }

  @Test
  void theBackendGatesRefusalsPassThroughWithTheGatewayGatesCodeStatusAndDetail() {
    assertGate(503, "EXCHANGE_DISABLED", ExchangeGateFilter.EXCHANGE_DISABLED_DETAIL);
    assertGate(503, "REGISTRY_UNAVAILABLE", ExchangeGateFilter.REGISTRY_UNAVAILABLE_DETAIL);
    assertGate(403, "CLIENT_NOT_ALLOWED", ExchangeGateFilter.CLIENT_NOT_ALLOWED_DETAIL);
    assertGate(403, "CLIENT_SUSPENDED", ExchangeGateFilter.CLIENT_SUSPENDED_DETAIL);
    assertGate(401, "INSTALLATION_REVOKED", ExchangeGateFilter.INSTALLATION_REVOKED_DETAIL);
    assertGate(401, "CLIENT_REVOKED", ExchangeGateFilter.CLIENT_REVOKED_DETAIL);
    assertGate(403, "SCOPE_MISSING", ExchangeGateFilter.SCOPE_MISSING_DETAIL);
    assertThat(ExchangeRelay.GATE_STATUSES).hasSize(7);
    assertThat(count("refused")).isEqualTo(7.0d);
  }

  @Test
  void aGateCodeWithAnotherStatusOrAnotherFiveHundredIsARelayFailure() {
    assertThat(interpret(403, "{\"code\":\"CLIENT_REVOKED\"}").code())
        .isEqualTo("BACKEND_RELAY_FAILED");
    assertThat(interpret(403, "{\"code\":\"EXCHANGE_DISABLED\"}").code())
        .isEqualTo("BACKEND_RELAY_FAILED");
    assertThat(interpret(503, "{\"code\":\"CLIENT_SUSPENDED\"}").code())
        .isEqualTo("BACKEND_RELAY_FAILED");
    assertThat(interpret(503, "{\"code\":\"NOT_PERMITTED\"}").code())
        .isEqualTo("BACKEND_RELAY_FAILED");
    assertThat(interpret(502, "{\"code\":\"EXTERNAL_SERVICE_ERROR\"}").status()).isEqualTo(502);
    assertThat(count("failed")).isEqualTo(5.0d);
  }

  @Test
  void theUnavailableGateCodesCarryTheGatewayGatesRetryAfter() {
    assertThat(ExchangeRelay.retryAfterSeconds("EXCHANGE_DISABLED")).isEqualTo("30");
    assertThat(ExchangeRelay.retryAfterSeconds("REGISTRY_UNAVAILABLE")).isEqualTo("30");
    assertThat(ExchangeRelay.retryAfterSeconds("CLIENT_SUSPENDED")).isNull();
    assertThat(ExchangeRelay.retryAfterSeconds(null)).isNull();
    assertThat(ExchangeRelay.isGateCode("CLIENT_SUSPENDED")).isTrue();
    assertThat(ExchangeRelay.isGateCode("NOT_PERMITTED")).isFalse();
    assertThat(ExchangeRelay.isGateCode(null)).isFalse();
  }

  @Test
  void anythingElseIsARelayFailure() {
    assertThat(interpret(401, "{\"code\":\"UNAUTHENTICATED\"}").code())
        .isEqualTo("BACKEND_RELAY_FAILED");
    assertThat(interpret(500, "{\"code\":\"INTERNAL_ERROR\"}").status()).isEqualTo(502);
    assertThat(interpret(404, "not json").code()).isEqualTo("BACKEND_RELAY_FAILED");
    assertThat(interpret(200, "").code()).isEqualTo("BACKEND_RELAY_FAILED");
    assertThat(interpret(403, "[1]").code()).isEqualTo("BACKEND_RELAY_FAILED");
    assertThat(count("failed")).isEqualTo(5.0d);
  }

  @Test
  void oddAnswersAreRelayFailures() {
    assertThat(interpret(403, "{\"code\":5}").code()).isEqualTo("BACKEND_RELAY_FAILED");
    assertThat(interpret(403, "{\"detail\":\"no code\"}").code()).isEqualTo("BACKEND_RELAY_FAILED");
    assertThat(interpret(302, "{\"code\":\"NOT_PERMITTED\"}").code())
        .isEqualTo("BACKEND_RELAY_FAILED");
    assertThat(interpret(403, "{\"code\":\"NOT_PERMITTED\",\"detail\":7}").detail())
        .isEqualTo(ExchangeRelay.DETAILS.get("NOT_PERMITTED"));
  }

  @Test
  void theBackendsDetailNeverReachesTheClient() {
    String leaky =
        "Blueprint 'Secret Name' of member 5f1d2c3b-0000-0000-0000-0000000000b2 not found:"
            + " SELECT * FROM personal_blueprint at de.greluc.krt.Internal";

    for (String code :
        new String[] {"BAD_REQUEST", "VALIDATION_FAILED", "ACCESS_DENIED", "OPTIMISTIC_LOCK"}) {
      ExchangeRelay.Result result =
          interpret(400, "{\"code\":\"" + code + "\",\"detail\":\"" + leaky + "\"}");

      assertThat(result.detail())
          .isEqualTo(ExchangeRelay.DETAILS.get(result.code()))
          .doesNotContain("Secret", "5f1d2c3b", "SELECT", "de.greluc");
    }
    for (String code : ExchangeRelay.PASSED_THROUGH) {
      int status = ExchangeRelay.GATE_STATUSES.getOrDefault(code, 409);
      assertThat(
              interpret(status, "{\"code\":\"" + code + "\",\"detail\":\"" + leaky + "\"}")
                  .detail())
          .isEqualTo(ExchangeRelay.DETAILS.get(code))
          .isNotBlank()
          .doesNotContain("Secret");
    }
  }

  /**
   * A {@code 401} or {@code 403} without a code the client may see refuses the gateway's own token,
   * so the relay drops it and the next call mints a fresh one.
   */
  @Test
  void aRefusalOfTheGatewaysIdentityDropsTheCachedToken() {
    for (HttpStatus status : new HttpStatus[] {HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN}) {
      backend
          .expect(requestTo("https://backend/api/v1/exchange/catalog/locations"))
          .andRespond(
              withStatus(status)
                  .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                  .body("{\"code\":\"UNAUTHENTICATED\"}"));
    }

    ExchangeRelay.Result unauthorized =
        relay.forward(HttpMethod.GET, "/api/v1/exchange/catalog/locations", null, context(), null);
    ExchangeRelay.Result forbidden =
        relay.forward(HttpMethod.GET, "/api/v1/exchange/catalog/locations", null, context(), null);

    backend.verify();
    assertThat(unauthorized.code()).isEqualTo("BACKEND_RELAY_FAILED");
    assertThat(forbidden.code()).isEqualTo("BACKEND_RELAY_FAILED");
    verify(tokens, org.mockito.Mockito.times(2)).invalidate();
  }

  /**
   * A refusal the client may see concerns the member or the client, not the gateway, so the token
   * stays cached.
   */
  @Test
  void aRefusalOfTheMemberOrTheClientKeepsTheToken() {
    assertThat(interpret(403, "{\"code\":\"TERMS_NOT_ACCEPTED\"}").code())
        .isEqualTo("TERMS_NOT_ACCEPTED");
    assertThat(interpret(403, "{\"code\":\"ACCESS_DENIED\"}").code()).isEqualTo("NOT_PERMITTED");
    assertThat(interpret(401, "{\"code\":\"INSTALLATION_REVOKED\"}").code())
        .isEqualTo("INSTALLATION_REVOKED");
    assertThat(interpret(500, "{}").code()).isEqualTo("BACKEND_RELAY_FAILED");

    verify(tokens, never()).invalidate();
  }

  @Test
  void aBackendErrorStatusIsInterpretedNotThrown() {
    backend
        .expect(requestTo("https://backend/api/v1/exchange/catalog/resolve"))
        .andRespond(
            withStatus(HttpStatus.FORBIDDEN)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body("{\"code\":\"NO_ROLE\"}"));

    ExchangeRelay.Result result =
        relay.forward(
            HttpMethod.POST,
            "/api/v1/exchange/catalog/resolve",
            mapper.readTree("{\"kind\":\"ITEM\",\"refs\":[{\"name\":\"x\"}]}"),
            context(),
            null);

    assertThat(result.code()).isEqualTo("NO_ROLE");
  }

  @Test
  void anUnreachableBackendIsARelayFailureAndCounted() {
    backend
        .expect(requestTo("https://backend/api/v1/exchange/catalog/locations"))
        .andRespond(
            request -> {
              throw new IOException("connection refused");
            });

    ExchangeRelay.Result result =
        relay.forward(HttpMethod.GET, "/api/v1/exchange/catalog/locations", null, context(), null);

    assertThat(result.status()).isEqualTo(502);
    assertThat(result.code()).isEqualTo("BACKEND_RELAY_FAILED");
    assertThat(count("failed")).isEqualTo(1.0d);
  }

  @Test
  void anOpenCircuitIsARelayFailureAndCounted() {
    CircuitBreakerRegistry breakers = CircuitBreakerRegistry.ofDefaults();
    breakers.circuitBreaker(ExchangeRelay.BREAKER).transitionToForcedOpenState();
    ServiceAccountTokenProvider tokens = mock(ServiceAccountTokenProvider.class);
    when(tokens.currentToken()).thenReturn("gateway-token");
    ExchangeRelay open =
        relay(RestClient.builder().baseUrl("https://backend").build(), tokens, breakers, bulkheads);

    ExchangeRelay.Result result =
        open.forward(HttpMethod.GET, "/api/v1/exchange/catalog/locations", null, context(), null);

    assertThat(result.code()).isEqualTo("BACKEND_RELAY_FAILED");
    assertThat(count("failed")).isEqualTo(1.0d);
  }

  @Test
  void aMissingGatewayTokenIsARelayFailureAndCounted() {
    ServiceAccountTokenProvider tokens = mock(ServiceAccountTokenProvider.class);
    when(tokens.currentToken())
        .thenThrow(
            new ServiceAccountTokenProvider.ServiceAccountTokenException("no identity", null));
    ExchangeRelay tokenless =
        relay(
            RestClient.builder().baseUrl("https://backend").build(),
            tokens,
            CircuitBreakerRegistry.ofDefaults(),
            bulkheads);

    ExchangeRelay.Result result =
        tokenless.forward(
            HttpMethod.GET, "/api/v1/exchange/catalog/locations", null, context(), null);

    assertThat(result.code()).isEqualTo("BACKEND_RELAY_FAILED");
    assertThat(count("failed")).isEqualTo(1.0d);
  }

  @Test
  void aLargeChangeSetWithoutAFreeSlotIsRefusedBusyWithoutReachingTheBackend() {
    Bulkhead slots = bulkheads.bulkhead(ExchangeRelay.LARGE_CHANGE_SETS);
    assertThat(slots.tryAcquirePermission()).isTrue();

    ExchangeRelay.Result result =
        relay.forwardLarge(
            HttpMethod.POST, "/api/v1/exchange/me/stock/changes", changeSet(), context(), null);

    backend.verify();
    assertThat(result.status()).isEqualTo(503);
    assertThat(result.code()).isEqualTo(ExchangeRefusals.RELAY_BUSY);
    assertThat(ExchangeRelay.retryAfterSeconds(result.code()))
        .isEqualTo(ExchangeRelay.BUSY_RETRY_AFTER_SECONDS);
    assertThat(
            meters
                .get(MetricNames.EXCHANGE_REFUSED)
                .tag(MetricNames.TAG_REASON, "relay_busy")
                .tag(MetricNames.TAG_CLIENT_ID, "versekit")
                .counter()
                .count())
        .isEqualTo(1.0d);
    slots.onComplete();
  }

  @Test
  void aLargeChangeSetTakesASlotOnlyWhileItIsRelayed() {
    backend
        .expect(requestTo("https://backend/api/v1/exchange/me/stock/changes"))
        .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
    backend
        .expect(requestTo("https://backend/api/v1/exchange/me/stock/changes"))
        .andRespond(withSuccess(RESULT, MediaType.APPLICATION_JSON));

    ExchangeRelay.Result failed =
        relay.forwardLarge(
            HttpMethod.POST, "/api/v1/exchange/me/stock/changes", changeSet(), context(), null);
    ExchangeRelay.Result next =
        relay.forwardLarge(
            HttpMethod.POST, "/api/v1/exchange/me/stock/changes", changeSet(), context(), null);

    backend.verify();
    assertThat(failed.code()).isEqualTo("BACKEND_RELAY_FAILED");
    assertThat(next.isOk()).isTrue();
    assertThat(
            bulkheads
                .bulkhead(ExchangeRelay.LARGE_CHANGE_SETS)
                .getMetrics()
                .getAvailableConcurrentCalls())
        .isEqualTo(1);
  }

  @Test
  void aSmallRequestNeedsNoSlot() {
    Bulkhead slots = bulkheads.bulkhead(ExchangeRelay.LARGE_CHANGE_SETS);
    assertThat(slots.tryAcquirePermission()).isTrue();
    backend
        .expect(requestTo("https://backend/api/v1/exchange/me/stock/changes"))
        .andRespond(withSuccess(RESULT, MediaType.APPLICATION_JSON));

    ExchangeRelay.Result result =
        relay.forward(
            HttpMethod.POST, "/api/v1/exchange/me/stock/changes", changeSet(), context(), null);

    backend.verify();
    assertThat(result.isOk()).isTrue();
    slots.onComplete();
  }

  /**
   * Builds a relay on the given collaborators.
   *
   * @param client the backend client
   * @param tokens the gateway identity
   * @param breakers the breaker registry
   * @param bulkheadRegistry the bulkhead registry
   * @return the relay
   */
  private ExchangeRelay relay(
      RestClient client,
      ServiceAccountTokenProvider tokens,
      CircuitBreakerRegistry breakers,
      BulkheadRegistry bulkheadRegistry) {
    return new ExchangeRelay(
        client,
        tokens,
        breakers,
        bulkheadRegistry,
        new ExchangeRefusals(meters, mock(ExchangeRegistryReader.class)),
        mapper,
        meters,
        TestLoggingProperties.defaults());
  }

  /**
   * Builds a change set body.
   *
   * @return the body
   */
  private JsonNode changeSet() {
    return mapper.readTree("{\"ops\":[]}");
  }

  /**
   * Asserts a backend refusal with a gate code passes through unchanged but for the detail.
   *
   * @param status the status both gates answer the code with
   * @param code the gate code
   * @param detail the gateway gate's detail for it
   */
  private void assertGate(int status, String code, String detail) {
    ExchangeRelay.Result result =
        interpret(status, "{\"code\":\"" + code + "\",\"detail\":\"backend text\"}");

    assertThat(result.status()).isEqualTo(status);
    assertThat(result.code()).isEqualTo(code);
    assertThat(result.detail()).isEqualTo(detail);
  }

  /**
   * Interprets a raw answer.
   *
   * @param status the status
   * @param body the body
   * @return the result
   */
  private ExchangeRelay.Result interpret(int status, String body) {
    return relay.interpret(
        new ExchangeRelay.Raw(status, body.getBytes(StandardCharsets.UTF_8)),
        "/api/v1/exchange/x",
        "versekit");
  }

  /**
   * Builds an admitted context.
   *
   * @return the context
   */
  private static ExchangeRequestContext context() {
    return new ExchangeRequestContext(
        "versekit",
        "member-1",
        "jkt-1",
        Set.of("exchange.stock.read", "exchange.connect"),
        new ExchangeRegistry.Client(
            "VerseKit", true, Set.of("exchange.connect", "exchange.stock.read"), null, null, null),
        1_790_000_000L);
  }

  /**
   * Reads the relay outcome counter.
   *
   * @param outcome the outcome
   * @return the count
   */
  private double count(String outcome) {
    return meters
        .get(MetricNames.EXCHANGE_RELAY)
        .tag(MetricNames.TAG_OUTCOME, outcome)
        .tag(MetricNames.TAG_CLIENT_ID, "versekit")
        .counter()
        .count();
  }
}

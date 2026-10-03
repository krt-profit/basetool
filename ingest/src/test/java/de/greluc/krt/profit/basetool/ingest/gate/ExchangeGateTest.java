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

package de.greluc.krt.profit.basetool.ingest.gate;

import static de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport.BLUEPRINT_CHANGES;
import static de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport.CLIENT;
import static de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport.SERVICE_DOCUMENT;
import static de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport.STOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.nimbusds.jose.jwk.ECKey;
import de.greluc.krt.profit.basetool.ingest.auth.ExchangeTokenGateFilter;
import de.greluc.krt.profit.basetool.ingest.edge.CorrelationIdFilter;
import de.greluc.krt.profit.basetool.ingest.edge.RequestLoggingFilter;
import de.greluc.krt.profit.basetool.ingest.handoff.HandoffStagingService;
import de.greluc.krt.profit.basetool.ingest.observability.ExchangeLogContext;
import de.greluc.krt.profit.basetool.ingest.observability.MetricNames;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistry;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRegistryReader;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeRevocationReader;
import de.greluc.krt.profit.basetool.ingest.registry.ExchangeUnavailableException;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeBudget;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeIdempotency;
import de.greluc.krt.profit.basetool.ingest.store.ExchangeQuotas;
import de.greluc.krt.profit.basetool.ingest.support.ExchangeTestSupport;
import de.greluc.krt.profit.basetool.ingest.support.LogCapture;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The exchange registry gate end to end: route, switch, client, revocations, capabilities and
 * version (REQ-XCH-001, -003, -004, -008, -024).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(ExchangeTestSupport.ProbeRoutes.class)
class ExchangeGateTest {

  private static final String TOKEN = "gate-token";
  private static final String USER_AGENT = "VerseKit/2.4.0 (+https://example.org/versekit)";

  @Autowired private WebApplicationContext context;
  @Autowired private MeterRegistry meterRegistry;

  @MockitoBean private JwtDecoder jwtDecoder;
  @MockitoBean private HandoffStagingService handoffStagingService;
  @MockitoBean private ExchangeRegistryReader registryReader;
  @MockitoBean private ExchangeRevocationReader revocationReader;
  @MockitoBean private ExchangeQuotas quotas;
  @MockitoBean private ExchangeIdempotency idempotency;
  @MockitoBean private ExchangeBudget budget;

  private MockMvc mockMvc;
  private ECKey key;
  private String thumbprint;
  private String member;
  private Instant issuedAt;

  @BeforeEach
  void setUp() throws Exception {
    when(quotas.countWrite(anyString(), anyString()))
        .thenReturn(new ExchangeQuotas.Counted("ingest:xch:quota:test", 1L));
    mockMvc =
        MockMvcBuilders.webAppContextSetup(context)
            .addFilters(
                context.getBean(CorrelationIdFilter.class),
                context.getBean(RequestLoggingFilter.class))
            .apply(springSecurity())
            .build();
    key = ExchangeTestSupport.newKey();
    thumbprint = ExchangeTestSupport.thumbprint(key);
    member = UUID.randomUUID().toString();
    issuedAt = Instant.now().minusSeconds(60);
    tokenScopes("exchange.connect exchange.stock.read");
    registry(true, true, Set.of("exchange.connect", "exchange.stock.read"), null);
    when(revocationReader.isDenied(anyString())).thenReturn(false);
    when(idempotency.claim(anyString())).thenReturn(Optional.of("claim-token"));
    when(budget.reserve(anyString(), anyString(), anyString(), anyLong(), any())).thenReturn(true);
    when(budget.settle(
            anyString(), anyString(), anyString(), anyLong(), anyString(), anyLong(), any()))
        .thenReturn(true);
    when(revocationReader.revokedAt(anyString(), anyString())).thenReturn(null);
  }

  @Test
  void anAdmittedRequestCarriesTheClientAndTheCapabilitiesBothHold() throws Exception {
    tokenScopes("exchange.connect exchange.stock.read exchange.hangar.read");
    registry(
        true,
        true,
        Set.of("exchange.connect", "exchange.stock.read", "exchange.demand.read"),
        null);

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isOk())
        .andExpect(content().string(CLIENT + " exchange.connect exchange.stock.read"));
  }

  @Test
  void anAdmittedRequestIsLoggedWithItsClientAndRouteAndUntaggedAfterwards() {
    List<ILoggingEvent> lines =
        LogCapture.capture(
            RequestLoggingFilter.class,
            Level.INFO,
            () ->
                call(HttpMethod.GET, STOCK, ExchangeTestSupport.PROBE_MDC)
                    .andExpect(status().isOk())
                    .andExpect(content().string(CLIENT + " | GET " + STOCK)));

    assertThat(lines.getLast().getMDCPropertyMap())
        .containsEntry(ExchangeLogContext.CLIENT_KEY, CLIENT)
        .containsEntry(ExchangeLogContext.ROUTE_KEY, "GET " + STOCK);
    assertThat(MDC.get(ExchangeLogContext.CLIENT_KEY)).isNull();
    assertThat(MDC.get(ExchangeLogContext.ROUTE_KEY)).isNull();
  }

  @Test
  void theGateTagsTheRequestBeforeItsOwnChecksRun() throws Exception {
    AtomicReference<String> seen = new AtomicReference<>();
    when(revocationReader.isDenied(anyString()))
        .thenAnswer(
            invocation -> {
              seen.set(
                  MDC.get(ExchangeLogContext.CLIENT_KEY)
                      + " | "
                      + MDC.get(ExchangeLogContext.ROUTE_KEY));
              return true;
            });

    call(HttpMethod.GET, STOCK).andExpect(status().isUnauthorized());

    assertThat(seen.get()).isEqualTo(CLIENT + " | GET " + STOCK);
    assertThat(MDC.get(ExchangeLogContext.CLIENT_KEY)).isNull();
  }

  @Test
  void aClientOutsideTheRegistryIsLoggedAsUnregisteredNeverByItsOwnId() {
    when(registryReader.current())
        .thenReturn(
            new ExchangeRegistry(
                1L,
                true,
                Map.of(
                    "someone-else",
                    new ExchangeRegistry.Client(
                        "Else", true, Set.of("exchange.connect"), null, null, null))));

    List<ILoggingEvent> lines =
        LogCapture.capture(
            RequestLoggingFilter.class,
            Level.INFO,
            () -> call(HttpMethod.GET, STOCK).andExpect(status().isForbidden()));

    assertThat(lines.getLast().getMDCPropertyMap())
        .containsEntry(ExchangeLogContext.CLIENT_KEY, MetricNames.EXCHANGE_CLIENT_UNREGISTERED)
        .containsEntry(ExchangeLogContext.ROUTE_KEY, "GET " + STOCK);
    assertThat(MDC.get(ExchangeLogContext.CLIENT_KEY)).isNull();
  }

  @Test
  void anUnknownRouteIsNotFound() throws Exception {
    call(HttpMethod.GET, "/exchange/v1/me/secrets")
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void aKnownPathWithTheWrongMethodIsNotFound() throws Exception {
    call(HttpMethod.POST, STOCK).andExpect(status().isNotFound());
  }

  @Test
  void theSwitchOffRefusesWithRetryAfter() throws Exception {
    registry(false, true, Set.of("exchange.connect", "exchange.stock.read"), null);

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string(HttpHeaders.RETRY_AFTER, "30"))
        .andExpect(jsonPath("$.code").value("EXCHANGE_DISABLED"));
  }

  @Test
  void anUnreadableRegistryFailsClosed() throws Exception {
    when(registryReader.current()).thenThrow(new ExchangeUnavailableException("down", null));
    double before = refused("registry_unavailable");
    double unknown = refusedBy("registry_unavailable", MetricNames.EXCHANGE_CLIENT_UNKNOWN);

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("REGISTRY_UNAVAILABLE"));

    assertThat(refused("registry_unavailable") - before).isEqualTo(1.0d);
    assertThat(refusedBy("registry_unavailable", MetricNames.EXCHANGE_CLIENT_UNKNOWN) - unknown)
        .isEqualTo(1.0d);
  }

  @Test
  void unreadableRevocationsFailClosedAndStillNameTheClient() throws Exception {
    when(revocationReader.isDenied(anyString()))
        .thenThrow(new ExchangeUnavailableException("down", null));
    double before = refusedBy("registry_unavailable", CLIENT);

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("REGISTRY_UNAVAILABLE"));

    assertThat(refusedBy("registry_unavailable", CLIENT) - before).isEqualTo(1.0d);
  }

  @Test
  void aClientOutsideTheRegistryIsNotAllowed() throws Exception {
    when(registryReader.current()).thenReturn(new ExchangeRegistry(1L, true, Map.of()));
    double unregistered = refusedBy("client_not_allowed", MetricNames.EXCHANGE_CLIENT_UNREGISTERED);

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CLIENT_NOT_ALLOWED"));

    assertThat(
            refusedBy("client_not_allowed", MetricNames.EXCHANGE_CLIENT_UNREGISTERED)
                - unregistered)
        .as("an azp the registry does not list never becomes a label value of its own")
        .isEqualTo(1.0d);
    assertThat(refusedBy("client_not_allowed", CLIENT)).isZero();
  }

  @Test
  void aSuspendedClientIsRefusedAndCountedUnderItsOwnId() throws Exception {
    registry(true, false, Set.of("exchange.connect", "exchange.stock.read"), null);
    double before = refusedBy("client_suspended", CLIENT);

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CLIENT_SUSPENDED"));

    assertThat(refusedBy("client_suspended", CLIENT) - before).isEqualTo(1.0d);
  }

  @Test
  void aDeniedInstallationIsRefusedWhateverTheTokensAge() throws Exception {
    when(revocationReader.isDenied(thumbprint)).thenReturn(true);

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("INSTALLATION_REVOKED"));
  }

  @Test
  void aTokenIssuedBeforeTheClientRevocationIsRefused() throws Exception {
    when(revocationReader.revokedAt(CLIENT, member)).thenReturn(issuedAt.getEpochSecond() + 10);

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("CLIENT_REVOKED"));
  }

  @Test
  void aFreshConnectionAfterTheRevocationWorksAtOnce() throws Exception {
    when(revocationReader.revokedAt(CLIENT, member)).thenReturn(issuedAt.getEpochSecond() - 10);

    call(HttpMethod.GET, STOCK).andExpect(status().isOk());
  }

  @Test
  void aTokenRefreshedAfterTheDisconnectFromAnOldSignInIsRefused() throws Exception {
    long revokedAt = issuedAt.getEpochSecond() - 30;
    when(revocationReader.revokedAt(CLIENT, member)).thenReturn(revokedAt);
    token(
        "exchange.connect exchange.stock.read",
        issuedAt,
        Instant.ofEpochSecond(revokedAt).minusSeconds(3600));

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("CLIENT_REVOKED"));
  }

  @Test
  void aNewSignInAfterTheDisconnectIsAdmitted() throws Exception {
    long revokedAt = issuedAt.getEpochSecond() - 30;
    when(revocationReader.revokedAt(CLIENT, member)).thenReturn(revokedAt);
    token(
        "exchange.connect exchange.stock.read",
        issuedAt,
        Instant.ofEpochSecond(revokedAt).plusSeconds(5));

    call(HttpMethod.GET, STOCK).andExpect(status().isOk());
  }

  @Test
  void aSignInInTheSecondOfTheDisconnectIsRefused() throws Exception {
    long revokedAt = issuedAt.getEpochSecond() - 30;
    when(revocationReader.revokedAt(CLIENT, member)).thenReturn(revokedAt);
    token("exchange.connect exchange.stock.read", issuedAt, Instant.ofEpochSecond(revokedAt));

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("CLIENT_REVOKED"));
  }

  @Test
  void anOnlineTokenWithoutAuthTimeIsRefusedOnceTheClientWasDisconnected() throws Exception {
    token("exchange.connect exchange.stock.read", issuedAt, null);

    call(HttpMethod.GET, STOCK).andExpect(status().isOk());

    when(revocationReader.revokedAt(CLIENT, member)).thenReturn(issuedAt.getEpochSecond() - 30);
    call(HttpMethod.GET, STOCK)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("CLIENT_REVOKED"));
  }

  @Test
  void anOfflineTokenIssuedAfterTheDisconnectIsAdmittedWhateverItsSignIn() throws Exception {
    long revokedAt = issuedAt.getEpochSecond() - 30;
    when(revocationReader.revokedAt(CLIENT, member)).thenReturn(revokedAt);
    token(
        "exchange.connect exchange.stock.read offline_access",
        issuedAt,
        Instant.ofEpochSecond(revokedAt).minusSeconds(3600));

    call(HttpMethod.GET, STOCK).andExpect(status().isOk());
  }

  @Test
  void theConnectionTimeIsTheTimeTheRevocationIsComparedWith() {
    Instant signIn = issuedAt.minusSeconds(3600);
    Jwt online =
        ExchangeTestSupport.token(
            TOKEN, "basetool-ingest", thumbprint, member, "exchange.connect", issuedAt, signIn);
    Jwt offline =
        ExchangeTestSupport.token(
            TOKEN,
            "basetool-ingest",
            thumbprint,
            member,
            "exchange.connect offline_access",
            issuedAt,
            signIn);
    Jwt withoutSignIn =
        ExchangeTestSupport.token(
            TOKEN, "basetool-ingest", thumbprint, member, "exchange.connect", issuedAt, null);

    assertThat(ExchangeGateFilter.connectionTime(online, Set.of("exchange.connect")))
        .isEqualTo(Instant.ofEpochSecond(signIn.getEpochSecond()));
    assertThat(
            ExchangeGateFilter.connectionTime(offline, Set.of("exchange.connect", "offline_access"))
                .getEpochSecond())
        .isEqualTo(issuedAt.getEpochSecond());
    assertThat(ExchangeGateFilter.connectionTime(withoutSignIn, Set.of("exchange.connect")))
        .isNull();
    assertThat(ExchangeGateFilter.connectedAfter(null, 0L)).isFalse();
  }

  @Test
  void anOfflineTokenIssuedBeforeTheDisconnectIsRefused() throws Exception {
    when(revocationReader.revokedAt(CLIENT, member)).thenReturn(issuedAt.getEpochSecond());
    token("exchange.connect exchange.stock.read offline_access", issuedAt, issuedAt);

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("CLIENT_REVOKED"));
  }

  @Test
  void aCapabilityMissingFromTheTokenIsRefused() throws Exception {
    tokenScopes("exchange.connect");
    registry(true, true, Set.of("exchange.connect", "exchange.blueprints.write"), null);

    call(HttpMethod.POST, BLUEPRINT_CHANGES)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
  }

  @Test
  void aCapabilityTheRegistryDoesNotGrantIsRefused() throws Exception {
    tokenScopes("exchange.connect exchange.blueprints.write");
    registry(true, true, Set.of("exchange.connect", "exchange.stock.read"), null);

    call(HttpMethod.POST, BLUEPRINT_CHANGES)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
  }

  @Test
  void aCapabilityBothHoldIsAdmitted() throws Exception {
    tokenScopes("exchange.connect exchange.blueprints.write");
    registry(true, true, Set.of("exchange.connect", "exchange.blueprints.write"), null);

    call(HttpMethod.POST, BLUEPRINT_CHANGES).andExpect(status().isOk());
  }

  @Test
  void theServiceDocumentNeedsConnect() throws Exception {
    tokenScopes("exchange.blueprints.read");

    call(HttpMethod.GET, SERVICE_DOCUMENT)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("SCOPE_MISSING"));
  }

  @Test
  void anOldVersionIsRefusedAndACurrentOneAdmitted() throws Exception {
    registry(true, true, Set.of("exchange.connect", "exchange.stock.read"), "2.5.0");

    call(HttpMethod.GET, STOCK)
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("CLIENT_VERSION_UNSUPPORTED"));

    registry(true, true, Set.of("exchange.connect", "exchange.stock.read"), "2.4.0");
    call(HttpMethod.GET, STOCK).andExpect(status().isOk());
  }

  /**
   * Stubs the registry.
   *
   * @param enabled the switch
   * @param active the client's status
   * @param capabilities the grants
   * @param minVersion the minimum version, or {@code null}
   */
  private void registry(
      boolean enabled,
      boolean active,
      @NotNull Set<String> capabilities,
      @Nullable String minVersion) {
    when(registryReader.current())
        .thenReturn(ExchangeTestSupport.registry(enabled, active, capabilities, minVersion));
  }

  /**
   * Stubs the decoded token with the given scopes.
   *
   * @param scopes the space-separated scopes
   */
  private void tokenScopes(@NotNull String scopes) {
    when(jwtDecoder.decode(TOKEN))
        .thenReturn(
            ExchangeTestSupport.token(
                TOKEN, "basetool-ingest", thumbprint, member, scopes, issuedAt));
  }

  /**
   * Stubs the decoded token with the given scopes, issue time and sign-in time.
   *
   * @param scopes the space-separated scopes
   * @param issued the issue time
   * @param authTime the sign-in's time, or {@code null} for no {@code auth_time} claim
   */
  private void token(@NotNull String scopes, @NotNull Instant issued, @Nullable Instant authTime) {
    when(jwtDecoder.decode(TOKEN))
        .thenReturn(
            ExchangeTestSupport.token(
                TOKEN, "basetool-ingest", thumbprint, member, scopes, issued, authTime));
  }

  /**
   * Sends one DPoP-bound request with a valid nonce.
   *
   * @param method the method
   * @param path the path
   * @return the result
   * @throws Exception if the request fails
   */
  private @NotNull ResultActions call(@NotNull HttpMethod method, @NotNull String path)
      throws Exception {
    return call(method, path, "X-Probe-None");
  }

  /**
   * Sends one exchange request with an extra probe header set to {@code true}.
   *
   * @param method the method
   * @param path the path
   * @param probeHeader the extra header's name
   * @return the result of the second, nonce-carrying attempt
   * @throws Exception if the request fails
   */
  private @NotNull ResultActions call(
      @NotNull HttpMethod method, @NotNull String path, @NotNull String probeHeader)
      throws Exception {
    String nonce =
        mockMvc
            .perform(
                request(method, path)
                    .header(HttpHeaders.AUTHORIZATION, "DPoP " + TOKEN)
                    .header(
                        "DPoP", ExchangeTestSupport.proof(key, TOKEN, method.name(), path, null)))
            .andReturn()
            .getResponse()
            .getHeader(ExchangeTokenGateFilter.DPOP_NONCE_HEADER);
    return mockMvc.perform(
        request(method, path)
            .header(HttpHeaders.AUTHORIZATION, "DPoP " + TOKEN)
            .header(HttpHeaders.USER_AGENT, USER_AGENT)
            .header("Idempotency-Key", "gate-" + UUID.randomUUID())
            .header(probeHeader, "true")
            .header("DPoP", ExchangeTestSupport.proof(key, TOKEN, method.name(), path, nonce)));
  }

  /**
   * Reads the exchange refusal counter.
   *
   * @param reason the reason
   * @return the count
   */
  private double refused(@NotNull String reason) {
    return meterRegistry
        .get(MetricNames.EXCHANGE_REFUSED)
        .tag(MetricNames.TAG_REASON, reason)
        .counters()
        .stream()
        .mapToDouble(io.micrometer.core.instrument.Counter::count)
        .sum();
  }

  /**
   * Reads the refusal counter of one reason and one client label.
   *
   * @param reason the reason in snake case
   * @param client the {@code client_id} label
   * @return the count, zero when the series does not exist
   */
  private double refusedBy(@NotNull String reason, @NotNull String client) {
    io.micrometer.core.instrument.Counter counter =
        meterRegistry
            .find(MetricNames.EXCHANGE_REFUSED)
            .tag(MetricNames.TAG_REASON, reason)
            .tag(MetricNames.TAG_CLIENT_ID, client)
            .counter();
    return counter == null ? 0.0d : counter.count();
  }
}

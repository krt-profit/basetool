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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exchange.api.ExchangeProblemException;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.model.ExchangeCapability;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClient;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientRevocation;
import de.greluc.krt.profit.basetool.backend.model.ExchangeClientStatus;
import de.greluc.krt.profit.basetool.backend.model.ExchangeInstallation;
import de.greluc.krt.profit.basetool.backend.model.ExchangeSettings;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeClientRevocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeInstallationRepository;
import de.greluc.krt.profit.basetool.backend.repository.ExchangeSettingsRepository;
import de.greluc.krt.profit.basetool.backend.support.SubjectAuthentication;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class ExchangeGateTest {

  private static final java.util.UUID MEMBER =
      java.util.UUID.fromString("5f1d2c3b-0000-0000-0000-0000000000a1");
  private static final String KEY = "a".repeat(43);
  private static final Instant REVOKED_AT = Instant.parse("2026-09-27T10:00:00Z");
  private static final java.util.UUID CLIENT_ID =
      java.util.UUID.fromString("5f1d2c3b-0000-0000-0000-0000000000c1");

  private final ExchangeClientRepository clientRepository = mock(ExchangeClientRepository.class);
  private final ExchangeSettingsRepository settingsRepository =
      mock(ExchangeSettingsRepository.class);
  private final ExchangeInstallationRepository installationRepository =
      mock(ExchangeInstallationRepository.class);
  private final ExchangeClientRevocationRepository clientRevocationRepository =
      mock(ExchangeClientRevocationRepository.class);
  private final ExchangeRevocationMirror revocationMirror = mock(ExchangeRevocationMirror.class);
  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ExchangeGate gate =
      new ExchangeGate(
          clientRepository,
          settingsRepository,
          installationRepository,
          clientRevocationRepository,
          revocationMirror,
          meterRegistry);

  private ExchangeClient client;
  private ExchangeSettings settings;

  @BeforeEach
  void setUp() {
    client = new ExchangeClient();
    client.setId(CLIENT_ID);
    client.setClientId("versekit");
    client.setStatus(ExchangeClientStatus.ACTIVE);
    client.setCapabilities(EnumSet.of(ExchangeCapability.CONNECT, ExchangeCapability.STOCK_READ));
    settings = new ExchangeSettings();
    settings.setEnabled(true);
    when(settingsRepository.findById(ExchangeSettings.SINGLETON_ID))
        .thenReturn(Optional.of(settings));
    when(clientRepository.findWithCapabilitiesByClientId("versekit"))
        .thenReturn(Optional.of(client));
  }

  @Test
  void allowsARelayedAndGrantedCapability() {
    assertThat(gate.allows("exchange.stock.read", acting("versekit", "exchange.stock.read")))
        .isTrue();
    assertThat(gate.allowsAny(acting("versekit", "exchange.connect"))).isTrue();
  }

  @Test
  void refusesACapabilityThatWasRelayedButNotGrantedAsTheGatewayDoes() {
    assertRefused(
        () -> gate.allows("exchange.stock.write", acting("versekit", "exchange.stock.write")),
        HttpStatus.FORBIDDEN,
        "SCOPE_MISSING");
    assertRefused(
        () -> gate.allowsAny(acting("versekit", "exchange.stock.write")),
        HttpStatus.FORBIDDEN,
        "SCOPE_MISSING");
    assertThat(refused(ExchangeGate.REASON_SCOPE_MISSING)).isEqualTo(2);
  }

  @Test
  void refusesACapabilityThatWasGrantedButNotRelayedAsTheGatewayDoes() {
    assertRefused(
        () -> gate.allows("exchange.stock.read", acting("versekit", "exchange.connect")),
        HttpStatus.FORBIDDEN,
        "SCOPE_MISSING");
  }

  @Test
  void refusesAnythingButARelayedActingMemberWithoutAnExchangeCode() {
    assertThat(gate.allowsAny(null)).isFalse();
    assertThat(
            gate.allowsAny(
                new TestingAuthenticationToken("s", "c", "ROLE_EXCHANGE_MEMBER", "ROLE_ADMIN")))
        .isFalse();
    assertThat(gate.allowsAny(acting(null, "exchange.connect"))).isFalse();
    assertThat(refused(ExchangeGate.REASON_NOT_RELAYED)).isEqualTo(3);
  }

  @Test
  void refusesWhileTheSwitchIsOffAsTheGatewayDoes() {
    settings.setEnabled(false);

    assertRefused(
        () -> gate.allowsAny(acting("versekit", "exchange.connect")),
        HttpStatus.SERVICE_UNAVAILABLE,
        "EXCHANGE_DISABLED");
    assertThat(refused(ExchangeGate.REASON_SWITCH_OFF)).isEqualTo(1);
  }

  @Test
  void refusesAnUnknownOrSuspendedClientAsTheGatewayDoes() {
    when(clientRepository.findWithCapabilitiesByClientId("stranger")).thenReturn(Optional.empty());
    assertRefused(
        () -> gate.allowsAny(acting("stranger", "exchange.connect")),
        HttpStatus.FORBIDDEN,
        "CLIENT_NOT_ALLOWED");

    client.setStatus(ExchangeClientStatus.SUSPENDED);
    assertRefused(
        () -> gate.allows("exchange.stock.read", acting("versekit", "exchange.stock.read")),
        HttpStatus.FORBIDDEN,
        "CLIENT_SUSPENDED");

    assertThat(refused(ExchangeGate.REASON_CLIENT_UNKNOWN)).isEqualTo(1);
    assertThat(refused(ExchangeGate.REASON_CLIENT_SUSPENDED)).isEqualTo(1);
  }

  @Test
  void refusesARevokedInstallationAsTheGatewayDoes() {
    ExchangeInstallation revoked = new ExchangeInstallation();
    revoked.setRevokedAt(REVOKED_AT);
    when(installationRepository.findByKey("versekit", MEMBER, KEY))
        .thenReturn(Optional.of(revoked));

    assertRefused(
        () -> gate.allowsAny(acting("versekit", "exchange.connect")),
        HttpStatus.UNAUTHORIZED,
        "INSTALLATION_REVOKED");
    assertThat(refused(ExchangeGate.REASON_INSTALLATION_REVOKED)).isEqualTo(1);
  }

  @Test
  void refusesAConnectionMadeAtOrBeforeTheMembersDisconnectOfTheClient() {
    when(revocationMirror.revokedAt("versekit", MEMBER)).thenReturn(REVOKED_AT);
    long second = REVOKED_AT.getEpochSecond();

    assertRefused(
        () -> gate.allowsAny(acting("versekit", second - 60, "exchange.connect")),
        HttpStatus.UNAUTHORIZED,
        "CLIENT_REVOKED");
    assertRefused(
        () -> gate.allowsAny(acting("versekit", second, "exchange.connect")),
        HttpStatus.UNAUTHORIZED,
        "CLIENT_REVOKED");
    assertThat(refused(ExchangeGate.REASON_CLIENT_REVOKED)).isEqualTo(2);
  }

  @Test
  void allowsAConnectionMadeAfterTheDisconnect() {
    when(revocationMirror.revokedAt("versekit", MEMBER)).thenReturn(REVOKED_AT);

    assertThat(
            gate.allowsAny(acting("versekit", REVOKED_AT.getEpochSecond() + 1, "exchange.connect")))
        .isTrue();
  }

  @Test
  void aRequestRelayedWithoutAConnectionTimeCountsAsConnectedBeforeTheDisconnect() {
    assertThat(gate.allowsAny(acting("versekit", "exchange.connect"))).isTrue();

    when(revocationMirror.revokedAt("versekit", MEMBER)).thenReturn(REVOKED_AT);

    assertRefused(
        () -> gate.allowsAny(acting("versekit", "exchange.connect")),
        HttpStatus.UNAUTHORIZED,
        "CLIENT_REVOKED");
    assertThat(refused(ExchangeGate.REASON_CLIENT_REVOKED)).isEqualTo(1);
  }

  @Test
  void refusesAStoredDisconnectTheMirrorDoesNotCarry() {
    when(revocationMirror.revokedAt("versekit", MEMBER)).thenReturn(null);
    storedRevocation(REVOKED_AT);
    long second = REVOKED_AT.getEpochSecond();

    assertRefused(
        () -> gate.allowsAny(acting("versekit", second, "exchange.connect")),
        HttpStatus.UNAUTHORIZED,
        "CLIENT_REVOKED");
    assertThat(gate.allowsAny(acting("versekit", second + 1, "exchange.connect"))).isTrue();
    assertThat(refused(ExchangeGate.REASON_CLIENT_REVOKED)).isEqualTo(1);
  }

  @Test
  void comparesWithTheLaterOfTheStoredAndTheMirroredDisconnect() {
    Instant later = REVOKED_AT.plusSeconds(600);
    long between = REVOKED_AT.getEpochSecond() + 1;
    when(revocationMirror.revokedAt("versekit", MEMBER)).thenReturn(REVOKED_AT);
    storedRevocation(later);

    assertRefused(
        () -> gate.allowsAny(acting("versekit", between, "exchange.connect")),
        HttpStatus.UNAUTHORIZED,
        "CLIENT_REVOKED");

    when(revocationMirror.revokedAt("versekit", MEMBER)).thenReturn(later);
    storedRevocation(REVOKED_AT);

    assertRefused(
        () -> gate.allowsAny(acting("versekit", between, "exchange.connect")),
        HttpStatus.UNAUTHORIZED,
        "CLIENT_REVOKED");
    assertThat(gate.allowsAny(acting("versekit", later.getEpochSecond() + 1, "exchange.connect")))
        .isTrue();
  }

  @Test
  void anUnreadableMirrorFailsClosedAsTheGatewayDoes() {
    when(revocationMirror.revokedAt("versekit", MEMBER))
        .thenThrow(new RedisConnectionFailureException("down"));

    assertRefused(
        () -> gate.allowsAny(acting("versekit", REVOKED_AT.getEpochSecond(), "exchange.connect")),
        HttpStatus.SERVICE_UNAVAILABLE,
        "REGISTRY_UNAVAILABLE");
    assertThat(refused(ExchangeGate.REASON_REVOCATIONS_UNREADABLE)).isEqualTo(1);
  }

  /**
   * Stores the member's disconnect of the client in the database.
   *
   * @param revokedAt the stored revocation time
   */
  private void storedRevocation(@NotNull Instant revokedAt) {
    when(clientRevocationRepository.findById(new ExchangeClientRevocation.Key(CLIENT_ID, MEMBER)))
        .thenReturn(
            Optional.of(
                new ExchangeClientRevocation(
                    new ExchangeClientRevocation.Key(CLIENT_ID, MEMBER), revokedAt)));
  }

  /**
   * Asserts the gate refuses a call with an exchange problem of the given status and code.
   *
   * @param call the gate call
   * @param status the expected status
   * @param code the expected exchange code
   */
  private static void assertRefused(
      @NotNull ThrowingCallable call, @NotNull HttpStatus status, @NotNull String code) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            ExchangeProblemException.class,
            problem -> {
              assertThat(problem.status()).isEqualTo(status);
              assertThat(problem.code()).isEqualTo(code);
            });
  }

  /**
   * Builds an acting member's exchange authentication relayed without a connection time.
   *
   * @param externalClient the relayed client, or {@code null}
   * @param scopes the relayed scopes
   * @return the authentication
   */
  private static @NotNull Acting acting(
      @Nullable String externalClient, String @NotNull ... scopes) {
    return acting(externalClient, null, scopes);
  }

  /**
   * Builds an acting member's exchange authentication.
   *
   * @param externalClient the relayed client, or {@code null}
   * @param connectedAt the relayed connection time in epoch seconds, or {@code null}
   * @param scopes the relayed scopes
   * @return the authentication
   */
  private static @NotNull Acting acting(
      @Nullable String externalClient, @Nullable Long connectedAt, String @NotNull ... scopes) {
    List<SimpleGrantedAuthority> authorities =
        new java.util.ArrayList<>(List.of(new SimpleGrantedAuthority("ROLE_EXCHANGE_MEMBER")));
    for (String scope : scopes) {
      authorities.add(new SimpleGrantedAuthority("XCH_CAPABILITY:" + scope));
    }
    return new Acting(externalClient, connectedAt, authorities);
  }

  /**
   * Reads one refusal counter.
   *
   * @param reason the reason
   * @return the count
   */
  private double refused(@NotNull String reason) {
    return meterRegistry
        .counter(MetricNames.EXCHANGE_GATE_REFUSED, MetricNames.TAG_REASON, reason)
        .count();
  }

  /** A token-less exchange authentication, like the acting member's. */
  private static final class Acting extends AbstractAuthenticationToken
      implements SubjectAuthentication {

    private final @Nullable String externalClient;
    private final @Nullable Long connectedAt;

    /**
     * Creates it.
     *
     * @param externalClient the relayed client, or {@code null}
     * @param connectedAt the relayed connection time, or {@code null}
     * @param authorities the authorities
     */
    Acting(
        @Nullable String externalClient,
        @Nullable Long connectedAt,
        @NotNull List<SimpleGrantedAuthority> authorities) {
      super(authorities);
      this.externalClient = externalClient;
      this.connectedAt = connectedAt;
      setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
      return "";
    }

    @Override
    public Object getPrincipal() {
      return subject();
    }

    @Override
    public @NotNull String subject() {
      return MEMBER.toString();
    }

    @Override
    public @Nullable String exchangeInstallationKey() {
      return externalClient == null ? null : KEY;
    }

    @Override
    public @Nullable String externalClient() {
      return externalClient;
    }

    @Override
    public @Nullable Long exchangeConnectedAt() {
      return connectedAt;
    }
  }
}

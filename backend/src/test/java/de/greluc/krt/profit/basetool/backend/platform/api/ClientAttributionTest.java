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

package de.greluc.krt.profit.basetool.backend.platform.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.greluc.krt.profit.basetool.backend.exchange.api.IngestGatewayProperties;
import de.greluc.krt.profit.basetool.backend.exchange.internal.ExchangeClientDirectory;
import de.greluc.krt.profit.basetool.backend.exchange.internal.KnownExchangeClients;
import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import de.greluc.krt.profit.basetool.backend.platform.internal.ApiClientMetricsProperties;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Tests the one bounded answer to "which client is this" that both consumers read: the {@code
 * client_id} metric label (REQ-OBS-018) and the audit row's client column (REQ-AUDIT-005).
 *
 * <p>The point of the shared class is that those two agree, so these tests pin the mapping itself —
 * known verbatim, unknown bucketed, absent named — rather than either call site's use of it.
 */
class ClientAttributionTest {

  private ApiClientMetricsProperties clientProperties;
  private IngestGatewayProperties gatewayProperties;

  /** The gateway allowlist a test may extend; the properties record reads it by reference. */
  private List<String> gatewayClientIds;

  private KnownExchangeClients knownExchangeClients;

  private ClientAttribution attribution;

  @BeforeEach
  void setUp() {
    clientProperties =
        new ApiClientMetricsProperties(List.of("basetool-frontend", "basetool-android"));
    gatewayClientIds = new ArrayList<>();
    gatewayProperties = new IngestGatewayProperties(gatewayClientIds);
    knownExchangeClients = Mockito.mock(KnownExchangeClients.class);
    attribution =
        new ClientAttribution(
            clientProperties, new ExchangeClientDirectory(gatewayProperties, knownExchangeClients));
  }

  @Test
  void label_keepsAConfiguredClientVerbatim() {
    assertEquals("basetool-android", attribution.label("basetool-android"));
  }

  @Test
  void label_keepsAConfiguredGatewayVerbatimWithoutListingItTwice() {
    gatewayClientIds.add("basetool-ingest");

    assertEquals("basetool-ingest", attribution.label("basetool-ingest"));
  }

  @Test
  void label_collapsesAnUnregisteredClient() {
    assertEquals(MetricNames.CLIENT_ID_OTHER, attribution.label("someone-elses-client"));
  }

  @Test
  void label_namesTheAbsentClaimSeparatelyFromTheUnknownOne() {
    assertEquals(MetricNames.CLIENT_ID_NONE, attribution.label(null));
    assertEquals(MetricNames.CLIENT_ID_NONE, attribution.label("   "));
  }

  @Test
  void labelOf_readsTheClaimOutOfABearerToken() {
    Jwt jwt =
        Jwt.withTokenValue("t")
            .header("alg", "none")
            .claim("sub", "s")
            .claim("azp", "basetool-frontend")
            .build();

    assertEquals("basetool-frontend", attribution.labelOf(new JwtAuthenticationToken(jwt)));
  }

  @Test
  void label_keepsAnExchangeRegistryClientVerbatim() {
    Mockito.when(knownExchangeClients.isRegistered("versekit")).thenReturn(true);

    assertEquals("versekit", attribution.label("versekit"));
  }

  @Test
  void labelOf_namesTheExternalClientOfAnActingMember() {
    Mockito.when(knownExchangeClients.isRegistered("versekit")).thenReturn(true);

    assertEquals("versekit", attribution.labelOf(new ActingToken("versekit")));
    assertEquals(MetricNames.CLIENT_ID_NONE, attribution.labelOf(new ActingToken(null)));
  }

  @Test
  void relayedLabelOf_namesARegisteredClientOnlyFromTheGateway() {
    gatewayClientIds.add("basetool-ingest");
    Mockito.when(knownExchangeClients.isRegistered("versekit")).thenReturn(true);

    assertEquals("versekit", attribution.relayedLabelOf(bearer("basetool-ingest"), "versekit"));
    assertEquals(
        MetricNames.CLIENT_ID_OTHER,
        attribution.relayedLabelOf(bearer("basetool-ingest"), "not-registered"));
    assertEquals(
        "basetool-frontend",
        attribution.relayedLabelOf(bearer("basetool-frontend"), "versekit"),
        "a browser naming a client is labelled by its own token");
  }

  /**
   * Builds a bearer authentication issued to a client.
   *
   * @param azp the client the token was issued to
   * @return the authentication
   */
  private static JwtAuthenticationToken bearer(String azp) {
    return new JwtAuthenticationToken(
        Jwt.withTokenValue("t").header("alg", "none").claim("sub", "s").claim("azp", azp).build());
  }

  @Test
  void labelOf_answersForTokenlessAndAbsentAuthenticationsInsteadOfThrowing() {
    assertEquals(MetricNames.CLIENT_ID_NONE, attribution.labelOf(null));
    assertEquals(
        MetricNames.CLIENT_ID_NONE,
        attribution.labelOf(new TestingAuthenticationToken("principal", "creds")));
  }

  /** A token-less authentication that names an external client, like an acting member's. */
  private static final class ActingToken extends AbstractAuthenticationToken
      implements SubjectAuthentication {

    private final String externalClient;

    /**
     * Creates the token.
     *
     * @param externalClient the external client, or {@code null}
     */
    ActingToken(String externalClient) {
      super(List.of());
      this.externalClient = externalClient;
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
      return "5f1d2c3b-0000-0000-0000-000000000001";
    }

    @Override
    public String externalClient() {
      return externalClient;
    }
  }
}

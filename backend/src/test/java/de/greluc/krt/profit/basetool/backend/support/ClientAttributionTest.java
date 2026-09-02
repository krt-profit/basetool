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

package de.greluc.krt.profit.basetool.backend.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.greluc.krt.profit.basetool.backend.metrics.MetricNames;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
  private ClientAttribution attribution;

  @BeforeEach
  void setUp() {
    clientProperties = new ApiClientMetricsProperties();
    clientProperties.setKnownClientIds(List.of("basetool-frontend", "basetool-android"));
    gatewayProperties = new IngestGatewayProperties();
    attribution = new ClientAttribution(clientProperties, gatewayProperties);
  }

  @Test
  void label_keepsAConfiguredClientVerbatim() {
    assertEquals("basetool-android", attribution.label("basetool-android"));
  }

  @Test
  void label_keepsAConfiguredGatewayVerbatimWithoutListingItTwice() {
    // The gateway list is the deployment's other statement of "a machine client I know". Requiring
    // it in the metric allowlist as well is the drift that would make a gateway read as `other`.
    gatewayProperties.setClientIds(List.of("basetool-ingest"));

    assertEquals("basetool-ingest", attribution.label("basetool-ingest"));
  }

  @Test
  void label_collapsesAnUnregisteredClient() {
    assertEquals(MetricNames.CLIENT_ID_OTHER, attribution.label("someone-elses-client"));
  }

  @Test
  void label_namesTheAbsentClaimSeparatelyFromTheUnknownOne() {
    // `none` and `other` mean opposite things: nobody registered that client, versus every client
    // at once lost its azp (a Keycloak mapper regression). Collapsing them would hide the second.
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
  void labelOf_answersForTokenlessAndAbsentAuthenticationsInsteadOfThrowing() {
    // Both reach this from inside a business transaction (an audit row is written there), so the
    // only acceptable answer to "no token" is a value -- an exception would roll the mutation back.
    assertEquals(MetricNames.CLIENT_ID_NONE, attribution.labelOf(null));
    assertEquals(
        MetricNames.CLIENT_ID_NONE,
        attribution.labelOf(new TestingAuthenticationToken("principal", "creds")));
  }
}

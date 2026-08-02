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

package de.greluc.krt.profit.basetool.ingest.config;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.ingest.support.TestSslBundles;
import java.security.KeyStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.http.client.ClientHttpRequestFactory;

/**
 * Unit tests for the {@code keycloak-trust} pin (REQ-SEC-024). The two outcomes matter for very
 * different reasons: a registered bundle must produce a pinned client, and an absent bundle must
 * fall back to the JVM trust store (the dev/test shape) by returning {@code null} rather than
 * trusting nothing at all.
 */
class KeycloakTrustSupportTest {

  @Test
  void buildsAPinnedRequestFactoryFromARegisteredBundle() throws Exception {
    KeyStore truststore = KeyStore.getInstance("PKCS12");
    truststore.load(null, null);

    ClientHttpRequestFactory factory =
        KeycloakTrustSupport.trustedRequestFactory(
            TestSslBundles.withTrustStore(KeycloakTrustSupport.KEYCLOAK_TRUST_BUNDLE, truststore),
            KeycloakTrustSupport.KEYCLOAK_TRUST_BUNDLE);

    assertThat(factory).isNotNull();
  }

  @Test
  void returnsNullWhenNoSuchBundleIsRegistered() {
    // dev/test reach Keycloak over plain HTTP; the caller then uses its default client.
    ClientHttpRequestFactory factory =
        KeycloakTrustSupport.trustedRequestFactory(
            new DefaultSslBundleRegistry(), KeycloakTrustSupport.KEYCLOAK_TRUST_BUNDLE);

    assertThat(factory).isNull();
  }

  @Test
  void looksUpTheBundleByTheNameItIsAskedFor() {
    // The lookup is by name, so a bundle registered under a different name must not be picked up —
    // that would silently pin the JWKS fetch to the wrong trust anchor.
    ClientHttpRequestFactory factory =
        KeycloakTrustSupport.trustedRequestFactory(
            new DefaultSslBundleRegistry(), "some-other-bundle");

    assertThat(factory).isNull();
  }
}

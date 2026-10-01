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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.security.KeyStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslStoreBundle;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for the {@code keycloak-trust} pin (REQ-SEC-014): the pinned client must be bounded by
 * the timeouts of {@link RestClientConfig}, because it backs both the Keycloak Admin API client and
 * the internal JWKS decoder.
 */
class KeycloakTrustSupportTest {

  private static DefaultSslBundleRegistry registryWithEmptyTrustStore() throws Exception {
    KeyStore truststore = KeyStore.getInstance("PKCS12");
    truststore.load(null, null);
    DefaultSslBundleRegistry registry = new DefaultSslBundleRegistry();
    registry.registerBundle(
        KeycloakTrustSupport.KEYCLOAK_TRUST_BUNDLE,
        SslBundle.of(SslStoreBundle.of(null, null, truststore)));
    return registry;
  }

  @Test
  void pinnedFactoryCarriesTheConnectAndReadTimeoutsAndHttp11() throws Exception {
    ClientHttpRequestFactory factory =
        KeycloakTrustSupport.trustedRequestFactory(
            registryWithEmptyTrustStore(), KeycloakTrustSupport.KEYCLOAK_TRUST_BUNDLE);

    assertThat(factory).isNotNull();
    HttpClient client = (HttpClient) ReflectionTestUtils.getField(factory, "httpClient");
    assertThat(client).isNotNull();
    assertThat(client.connectTimeout()).contains(RestClientConfig.CONNECT_TIMEOUT);
    assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
    assertThat(ReflectionTestUtils.getField(factory, "readTimeout"))
        .isEqualTo(RestClientConfig.READ_TIMEOUT);
  }

  @Test
  void returnsNullWhenNoSuchBundleIsRegistered() {
    assertThat(
            KeycloakTrustSupport.trustedRequestFactory(
                new DefaultSslBundleRegistry(), KeycloakTrustSupport.KEYCLOAK_TRUST_BUNDLE))
        .isNull();
  }
}

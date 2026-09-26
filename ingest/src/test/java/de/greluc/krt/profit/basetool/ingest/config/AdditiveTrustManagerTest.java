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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;

/**
 * Unit tests asserting that adding one trust-anchor set never removes the other (ADR-0129): the
 * token endpoint may be validated by the JVM's default anchors or by the pinned {@code
 * keycloak-trust} bundle.
 */
class AdditiveTrustManagerTest {

  /** Accepts nothing at all — stands in for an anchor set that does not know this chain. */
  private static X509TrustManager rejecting() {
    return new X509TrustManager() {
      @Override
      public void checkClientTrusted(X509Certificate[] chain, String authType)
          throws CertificateException {
        throw new CertificateException("not my chain");
      }

      @Override
      public void checkServerTrusted(X509Certificate[] chain, String authType)
          throws CertificateException {
        throw new CertificateException("not my chain");
      }

      @Override
      public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
      }
    };
  }

  /** Accepts everything — stands in for the anchor set that does know this chain. */
  private static X509TrustManager accepting() {
    return new X509TrustManager() {
      @Override
      public void checkClientTrusted(X509Certificate[] chain, String authType) {}

      @Override
      public void checkServerTrusted(X509Certificate[] chain, String authType) {}

      @Override
      public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
      }
    };
  }

  /** A chain only the JVM defaults know — the public Keycloak. */
  @Test
  void acceptsAChainOnlyTheDefaultsValidate() {
    X509TrustManager composite = RestClientConfig.additiveTrustManager(accepting(), rejecting());

    assertThatCode(() -> composite.checkServerTrusted(new X509Certificate[0], "RSA"))
        .doesNotThrowAnyException();
  }

  /** A chain only the pinned bundle knows — an internal, self-signed Keycloak. */
  @Test
  void acceptsAChainOnlyThePinnedBundleValidates() {
    X509TrustManager composite = RestClientConfig.additiveTrustManager(rejecting(), accepting());

    assertThatCode(() -> composite.checkServerTrusted(new X509Certificate[0], "RSA"))
        .doesNotThrowAnyException();
  }

  /**
   * A chain neither knows is still refused — trusting both must not mean trusting anything.
   *
   * <p>The reported failure is the <em>public</em> one, because that is the one naming a real CA
   * problem; the pinned rejection is expected noise whenever the host is the public one and is
   * attached as suppressed rather than thrown.
   */
  @Test
  void stillRefusesAChainNeitherValidates() {
    X509TrustManager composite = RestClientConfig.additiveTrustManager(rejecting(), rejecting());

    assertThatThrownBy(() -> composite.checkServerTrusted(new X509Certificate[0], "RSA"))
        .isInstanceOf(CertificateException.class)
        .satisfies(thrown -> assertThat(thrown.getSuppressed()).hasSize(1));
  }

  /** The advertised issuers are the union, so neither anchor set disappears from the handshake. */
  @Test
  void advertisesTheUnionOfBothAnchorSets() {
    X509Certificate issuer = org.mockito.Mockito.mock(X509Certificate.class);
    X509TrustManager one =
        new X509TrustManager() {
          @Override
          public void checkClientTrusted(X509Certificate[] chain, String authType) {}

          @Override
          public void checkServerTrusted(X509Certificate[] chain, String authType) {}

          @Override
          public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[] {issuer};
          }
        };

    X509TrustManager composite = RestClientConfig.additiveTrustManager(one, one);

    assertThat(composite.getAcceptedIssuers()).hasSize(2);
  }
}

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
 * The property both shipped attempts violated: <strong>adding one anchor set must never remove the
 * other</strong> (ADR-0129).
 *
 * <p>The gateway's token endpoint may be the public Keycloak (validated by the JVM's default
 * anchors) or an internal one (validated by the pinned {@code keycloak-trust} bundle), and nothing
 * in the SSL configuration knows which was configured. Picking a single anchor set therefore breaks
 * whichever case was not picked. It broke twice in production, both times as {@code PKIX path
 * building failed}: first pinned to the <em>backend's</em> truststore, then to the pinned Keycloak
 * one.
 *
 * <p>Verified end to end before shipping by handshaking against the real public certificate with a
 * pinned store present — pinned-only reproduced the production failure, pinned-plus-defaults
 * succeeded. That check needs the internet, so what is pinned here is the logic underneath it.
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
      public void checkClientTrusted(X509Certificate[] chain, String authType) {
        // accepts
      }

      @Override
      public void checkServerTrusted(X509Certificate[] chain, String authType) {
        // accepts
      }

      @Override
      public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
      }
    };
  }

  /** A chain only the JVM defaults know — the public Keycloak. */
  @Test
  void acceptsAChainOnlyTheDefaultsValidate() {
    X509TrustManager composite = WebClientConfig.additiveTrustManager(accepting(), rejecting());

    assertThatCode(() -> composite.checkServerTrusted(new X509Certificate[0], "RSA"))
        .doesNotThrowAnyException();
  }

  /** A chain only the pinned bundle knows — an internal, self-signed Keycloak. */
  @Test
  void acceptsAChainOnlyThePinnedBundleValidates() {
    X509TrustManager composite = WebClientConfig.additiveTrustManager(rejecting(), accepting());

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
    X509TrustManager composite = WebClientConfig.additiveTrustManager(rejecting(), rejecting());

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
          public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // unused
          }

          @Override
          public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // unused
          }

          @Override
          public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[] {issuer};
          }
        };

    X509TrustManager composite = WebClientConfig.additiveTrustManager(one, one);

    assertThat(composite.getAcceptedIssuers()).hasSize(2);
  }
}

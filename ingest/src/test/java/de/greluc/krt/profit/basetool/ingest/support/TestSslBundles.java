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

package de.greluc.krt.profit.basetool.ingest.support;

import java.security.KeyStore;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;

/**
 * Registers an in-memory {@link SslBundle} so the truststore-pinning code paths can be exercised
 * without shipping a keystore file into the test tree — the project forbids reusing the production
 * {@code keystore.p12}, and a throwaway file would still have to be generated and cleaned up.
 */
public final class TestSslBundles {

  private TestSslBundles() {
    // Test-support holder — not instantiable.
  }

  /**
   * Builds an {@link SslBundles} registry containing exactly one bundle whose truststore is {@code
   * truststore} and whose keystore is absent.
   *
   * @param bundleName the name the bundle is registered under
   * @param truststore the trust store the bundle exposes; may deliberately be an unloaded {@link
   *     KeyStore} to exercise the fail-fast path
   * @return a registry serving that single bundle
   */
  public static SslBundles withTrustStore(String bundleName, KeyStore truststore) {
    DefaultSslBundleRegistry registry = new DefaultSslBundleRegistry();
    registry.registerBundle(bundleName, SslBundle.of(SslStoreBundle.of(null, null, truststore)));
    return registry;
  }
}

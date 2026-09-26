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

package de.greluc.krt.profit.basetool.frontend.config;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;

/**
 * Loads the committed test-stack TLS material for tests that need a real TLS server (ADR-0139).
 *
 * <p>{@code docker/test-tls/basetool-test-backend.p12} is a published throwaway, never a production
 * credential.
 */
final class TestTls {

  /** The keystore's password, published in {@code docker/test-tls/README.md}. */
  private static final char[] PASSWORD = "basetool-test".toCharArray();

  /** Not instantiable. */
  private TestTls() {}

  /**
   * Builds a {@link KeyManagerFactory} over the test keystore's server key.
   *
   * @return a factory initialised with the test server key
   * @throws IllegalStateException if the material cannot be found or read
   */
  static KeyManagerFactory serverKeyManagerFactory() {
    Path keystore = repoRoot().resolve("docker/test-tls/basetool-test-backend.p12");
    if (!Files.isReadable(keystore)) {
      throw new IllegalStateException(
          "test TLS material not readable at " + keystore.toAbsolutePath());
    }
    try (InputStream in = Files.newInputStream(keystore)) {
      KeyStore store = KeyStore.getInstance("PKCS12");
      store.load(in, PASSWORD);
      KeyManagerFactory factory =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      factory.init(store, PASSWORD);
      return factory;
    } catch (Exception e) {
      throw new IllegalStateException("cannot load the test TLS keystore at " + keystore, e);
    }
  }

  /**
   * Walks up from the working directory to the repository root, marked by {@code
   * settings.gradle.kts}.
   *
   * @return the repository root
   * @throws IllegalStateException when no ancestor carries the marker
   */
  private static Path repoRoot() {
    Path current = Path.of("").toAbsolutePath();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("no settings.gradle.kts above " + Path.of("").toAbsolutePath());
  }
}

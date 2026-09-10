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
 * The committed test-stack TLS material, loaded for tests that need a real TLS server.
 *
 * <p><b>This is not an exception to "never use production credentials in tests" — it is an
 * application of it</b> (ADR-0139). {@code docker/test-tls/basetool-test-keystore.p12} was built to
 * be published: the CA key that signed it was destroyed at generation time, the server key can only
 * serve loopback and docker-network names, and the subject of both certificates says {@code NOT FOR
 * PRODUCTION}. Production keeps its own keystore, bind-mounted at runtime, and {@code .gitignore}
 * refuses the exact name {@code keystore.p12} so the two cannot be confused.
 *
 * <p>Using it here rather than generating a throwaway certificate per test run is deliberate:
 * Netty's {@code SelfSignedCertificate} is deprecated and its replacement lives in an artefact this
 * project does not depend on, and a self-generated key would be one more piece of key material
 * appearing in a worktree for no gain.
 */
final class TestTls {

  /** The keystore's password, published in {@code docker/test-tls/README.md}. */
  private static final char[] PASSWORD = "basetool-test".toCharArray();

  /** Not instantiable. */
  private TestTls() {}

  /**
   * Builds a {@link KeyManagerFactory} over the test keystore's server key.
   *
   * <p>The keystore carries two aliases — {@code basetool} (the server key and its chain) and
   * {@code ca} (the anchor, certificate only) — and the factory selects the one that actually has a
   * key, so no alias needs naming here.
   *
   * @return a factory a TLS server can be built from, initialised with the test server key.
   * @throws IllegalStateException if the material cannot be found or read, which means the working
   *     directory assumption below no longer holds and the test would otherwise fail with a
   *     handshake error that says nothing about the cause.
   */
  static KeyManagerFactory serverKeyManagerFactory() {
    Path keystore = repoRoot().resolve("docker/test-tls/basetool-test-keystore.p12");
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
   * Walks up from the working directory until the repository root is found.
   *
   * <p>Gradle runs {@code :frontend:test} with {@code frontend/} as the working directory and an
   * IDE may not, so neither a relative {@code ../docker} nor an absolute path is safe. The marker
   * is {@code settings.gradle.kts}, the same anchor {@code ExternalContractTest} uses.
   *
   * @return the repository root.
   * @throws IllegalStateException when no ancestor carries the marker.
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

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Asserts the JWKS override lives under the application's own {@code app.security.jwt.jwk-set-uri}
 * and never under Boot's {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri}, where an
 * empty {@code ${VAR:}} default aborts context startup. Checks the declared YAML rather than a
 * booted context.
 */
class JwkSetUriNamespaceTest {

  /** Boot's resource-server key — fatal when declared with an empty value. */
  private static final String SPRING_KEY = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri";

  /** The application's own key — blank-tolerant by design (REQ-SEC-024). */
  private static final String APP_KEY = "app.security.jwt.jwk-set-uri";

  /**
   * No profile may declare Boot's resource-server {@code jwk-set-uri}; the override must use {@link
   * #APP_KEY}.
   *
   * @param yaml the profile configuration file to inspect
   * @throws IOException if the configuration file cannot be read
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "application.yml",
        "application-dev.yml",
        "application-prod.yml",
        "application-test.yml"
      })
  void noProfileDeclaresBootsResourceServerJwkSetUri(String yaml) throws IOException {
    assertThat(propertyOf(yaml, SPRING_KEY))
        .as(
            "%s must not declare %s — an empty value there fails the context with 'jwkSetUri cannot"
                + " be empty'; use %s",
            yaml, SPRING_KEY, APP_KEY)
        .isNull();
  }

  /**
   * The dev profile keeps the split-horizon escape hatch the Android emulator stack needs (its
   * tokens carry an issuer the backend cannot resolve), on the blank-tolerant namespace.
   *
   * @throws IOException if the configuration file cannot be read.
   */
  @Test
  void devKeepsTheEscapeHatchOnTheApplicationsOwnNamespace() throws IOException {
    assertThat(propertyOf("application-dev.yml", APP_KEY))
        .as("dev must keep the KEYCLOAK_JWK_SET_URI escape hatch on %s", APP_KEY)
        .isEqualTo("${KEYCLOAK_JWK_SET_URI:}");
  }

  /**
   * The prod profile keeps the JWKS override on the application's own namespace (REQ-SEC-024).
   *
   * @throws IOException if the configuration file cannot be read
   */
  @Test
  void prodKeepsTheEscapeHatchOnTheApplicationsOwnNamespace() throws IOException {
    assertThat(propertyOf("application-prod.yml", APP_KEY))
        .as("prod must keep the internal-JWKS knob on %s", APP_KEY)
        .isEqualTo("${KEYCLOAK_JWK_SET_URI:}");
  }

  /**
   * Production passes {@code IRI_BACKEND_KEYCLOAK_JWK_SET_URI} to the backend with an empty
   * default, which keeps the issuer-location decoder until it is set (REQ-SEC-024).
   *
   * @throws IOException if the environment template cannot be read
   */
  @Test
  void productionPassesTheKnobToTheBackendWithAnEmptyDefault() throws IOException {
    Path template = repositoryRoot().resolve("quadlet/env.d/backend.env.tmpl");
    assertThat(Files.readAllLines(template))
        .as("the backend's Quadlet environment must pass KEYCLOAK_JWK_SET_URI, empty by default")
        .contains("KEYCLOAK_JWK_SET_URI=${IRI_BACKEND_KEYCLOAK_JWK_SET_URI:-}");
  }

  /**
   * Walks up from the working directory to the repository root, marked by {@code
   * settings.gradle.kts}: Gradle runs this from {@code backend/}, an IDE may not.
   *
   * @return the repository root.
   */
  private static Path repositoryRoot() {
    for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
      if (Files.exists(p.resolve("settings.gradle.kts"))) {
        return p;
      }
    }
    throw new IllegalStateException(
        "repository root not found above " + Paths.get("").toAbsolutePath());
  }

  /**
   * Reads one raw declared property from a profile's YAML without booting a context or resolving
   * placeholders.
   *
   * @param yaml the classpath name of the configuration file
   * @param key the fully-qualified property key
   * @return the declared value, or {@code null} when the key is not declared
   * @throws IOException if the configuration file cannot be read
   */
  private static Object propertyOf(String yaml, String key) throws IOException {
    List<PropertySource<?>> sources =
        new YamlPropertySourceLoader().load(yaml, new ClassPathResource(yaml));
    return sources.stream()
        .map(source -> source.getProperty(key))
        .filter(Objects::nonNull)
        .findFirst()
        .orElse(null);
  }
}

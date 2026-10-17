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

package de.greluc.krt.profit.basetool.frontend.kernel.backend;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

/**
 * Pins the shipped default of the backend hostname check: on unless the environment turns it off,
 * the same as the ingest gateway (REQ-SEC-070).
 */
class BackendHostnameDefaultTest {

  /** With no {@code app.http} key set, the bound properties keep the hostname check on. */
  @Test
  void boundPropertiesVerifyTheHostnameByDefault() {
    Binder binder = new Binder(new MapConfigurationPropertySource(Map.of()));

    AppHttpProperties properties = binder.bindOrCreate("app.http", AppHttpProperties.class);

    assertThat(properties.verifyBackendHostname()).isTrue();
  }

  /**
   * The shipped configuration falls back to {@code true} when {@code INTERNAL_TLS_VERIFY_HOSTNAME}
   * is absent, and no profile file turns the check off.
   *
   * @throws IOException if a configuration file cannot be read from the classpath
   */
  @Test
  void shippedConfigurationVerifiesTheHostnameUnlessTheEnvironmentSaysOtherwise()
      throws IOException {
    assertThat(classpathText("application.yml"))
        .contains("verify-backend-hostname: ${INTERNAL_TLS_VERIFY_HOSTNAME:true}");
    assertThat(classpathText("application-dev.yml")).doesNotContain("verify-backend-hostname");
    assertThat(classpathText("application-prod.yml")).doesNotContain("verify-backend-hostname");
  }

  /** The readiness probe's own fallback agrees with the API client's default. */
  @Test
  void readinessProbeFallsBackToVerifyingTheHostname() {
    Constructor<?> constructor =
        Arrays.stream(BackendHealthIndicator.class.getConstructors())
            .filter(c -> c.getParameterCount() == 4)
            .findFirst()
            .orElseThrow();
    Parameter verifyHostname = constructor.getParameters()[3];

    assertThat(verifyHostname.getAnnotation(Value.class).value())
        .isEqualTo("${app.http.verify-backend-hostname:true}");
  }

  /**
   * Reads a classpath resource as UTF-8 text.
   *
   * @param name the resource name
   * @return the resource's content
   * @throws IOException if the resource is missing or cannot be read
   */
  private static String classpathText(String name) throws IOException {
    try (InputStream in =
        BackendHostnameDefaultTest.class.getClassLoader().getResourceAsStream(name)) {
      if (in == null) {
        throw new IOException("missing classpath resource " + name);
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}

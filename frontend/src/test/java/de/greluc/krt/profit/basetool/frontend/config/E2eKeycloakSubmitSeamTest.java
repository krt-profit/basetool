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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Verifies that every Playwright class submits the Keycloak login form through {@code
 * E2eSupport.submitKeycloakLogin}, reading the sources as text.
 */
class E2eKeycloakSubmitSeamTest {

  /** The Playwright sources, relative to the {@code frontend} module the test runs in. */
  private static final Path E2E_SOURCES =
      Path.of("src/e2e/java/de/greluc/krt/profit/basetool/frontend/e2e");

  /** The one class allowed to touch the Keycloak submit control. */
  private static final String SEAM = "E2eSupport.java";

  /** The Keycloak login form's submit control. */
  private static final String SUBMIT_SELECTOR = "\"#kc-login\"";

  /**
   * No Playwright class other than {@code E2eSupport} names the submit control, so no login can
   * click it without waiting for the credential POST.
   *
   * @throws IOException if the e2e source directory cannot be walked or a source cannot be read
   */
  @Test
  void noE2eClassSubmitsTheKeycloakFormOutsideTheSeam() throws IOException {
    List<String> offenders = new ArrayList<>();
    try (Stream<Path> sources = Files.list(E2E_SOURCES)) {
      for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
        String name = source.getFileName().toString();
        if (!SEAM.equals(name)
            && Files.readString(source, StandardCharsets.UTF_8).contains(SUBMIT_SELECTOR)) {
          offenders.add(name);
        }
      }
    }
    assertThat(offenders)
        .as(
            "submit the Keycloak login with E2eSupport.submitKeycloakLogin(page, user, password):"
                + " a bare click can be swallowed without a POST ever leaving")
        .isEmpty();
  }

  /**
   * The seam itself still names the submit control and waits for the credential POST, so the guard
   * above cannot pass merely because the selector changed.
   *
   * @throws IOException if {@code E2eSupport} cannot be read
   */
  @Test
  void theSeamClicksTheSubmitAndWaitsForThePost() throws IOException {
    String seam = Files.readString(E2E_SOURCES.resolve(SEAM), StandardCharsets.UTF_8);
    assertThat(seam)
        .contains(SUBMIT_SELECTOR)
        .contains("waitForRequest(")
        .contains("/login-actions/authenticate");
  }
}

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

package de.greluc.krt.profit.basetool.backend.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Pins the retirement of the {@code OFFICER} role (ADR-0300, REQ-SEC-004, REQ-SEC-047): no main
 * source or template of the backend or the frontend names the role constants or authority.
 */
class OfficerRoleRetiredSourceScanTest {

  private static final List<Path> SOURCE_ROOTS =
      List.of(
          Path.of("src/main/java"),
          Path.of("../frontend/src/main/java"),
          Path.of("../frontend/src/main/resources/templates"),
          Path.of("../frontend/src/main/resources/static/js"));

  private static final Pattern RETIRED_ROLE =
      Pattern.compile(
          "Roles\\.OFFICER\\b|ROLE_OFFICER\\b|\\bADMIN_OR_OFFICER\\b|hasRole\\('OFFICER'\\)"
              + "|hasAnyRole\\([^)]*'OFFICER'");

  @Test
  void noMainSourceNamesTheRetiredOfficerRole() throws IOException {
    List<String> offenders = new java.util.ArrayList<>();
    for (Path root : SOURCE_ROOTS) {
      try (Stream<Path> files = Files.walk(root)) {
        for (Path file : files.filter(Files::isRegularFile).toList()) {
          if (RETIRED_ROLE.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
            offenders.add(file.toString());
          }
        }
      }
    }

    assertThat(offenders).isEmpty();
  }
}

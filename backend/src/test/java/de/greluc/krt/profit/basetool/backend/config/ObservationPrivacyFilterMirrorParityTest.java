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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Enforces the hand-maintained cross-module mirror of {@code ObservationPrivacyFilter}. The filter
 * — which strips PII from Micrometer observation names — is deliberately copied verbatim into the
 * {@code backend}, {@code frontend} and {@code ingest} modules (ADR-0072: no shared library module
 * without owner sign-off) and kept in sync only by a Javadoc convention. This test makes the mirror
 * mechanical: it reads the three source files and asserts they are byte-identical apart from their
 * {@code package} declaration, so an edit to one copy that is not propagated to the others fails
 * the build instead of silently letting a module leak what the others redact.
 *
 * <p>Only {@code ObservationPrivacyFilter} is guarded here: the other cross-module twins ({@code
 * MonitoringScrapeProperties}, {@code NotificationStreamObservationPredicate}, {@code
 * StringNormalization}) carry legitimate per-module differences and are NOT byte-identical, so a
 * parity assertion would be wrong for them.
 */
class ObservationPrivacyFilterMirrorParityTest {

  /**
   * Asserts the frontend and ingest copies of {@code ObservationPrivacyFilter} match the backend
   * copy once the differing {@code package} line is removed.
   *
   * @throws IOException if any of the three source files cannot be read
   */
  @Test
  void observationPrivacyFilterIsByteIdenticalAcrossModules() throws IOException {
    Path repoRoot = findRepoRoot();
    String backend =
        normalise(
            repoRoot.resolve(
                "backend/src/main/java/de/greluc/krt/profit/basetool/backend/config/"
                    + "ObservationPrivacyFilter.java"));
    String frontend =
        normalise(
            repoRoot.resolve(
                "frontend/src/main/java/de/greluc/krt/profit/basetool/frontend/config/"
                    + "ObservationPrivacyFilter.java"));
    String ingest =
        normalise(
            repoRoot.resolve(
                "ingest/src/main/java/de/greluc/krt/profit/basetool/ingest/config/"
                    + "ObservationPrivacyFilter.java"));

    assertThat(frontend)
        .as(
            "frontend ObservationPrivacyFilter must mirror the backend copy verbatim (modulo the "
                + "package line) — propagate the edit to all three modules")
        .isEqualTo(backend);
    assertThat(ingest)
        .as(
            "ingest ObservationPrivacyFilter must mirror the backend copy verbatim (modulo the "
                + "package line) — propagate the edit to all three modules")
        .isEqualTo(backend);
  }

  /**
   * Reads a Java source file and returns its content with the leading {@code package} declaration
   * removed and line endings normalised, so only the logical body is compared.
   *
   * @param source the source file to read
   * @return the file's content minus its {@code package} line, lines joined with {@code \n}
   * @throws IOException if the file cannot be read
   */
  private static String normalise(Path source) throws IOException {
    return Files.readAllLines(source, StandardCharsets.UTF_8).stream()
        .filter(line -> !line.startsWith("package "))
        .collect(Collectors.joining("\n"));
  }

  /**
   * Walks up from the test's working directory until the directory holding {@code
   * settings.gradle.kts} is found, so the cross-module source paths resolve regardless of which
   * module's directory the test task runs in.
   *
   * @return the repository root directory
   * @throws IllegalStateException if no {@code settings.gradle.kts} is found on the way up
   */
  private static Path findRepoRoot() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
      dir = dir.getParent();
    }
    if (dir == null) {
      throw new IllegalStateException(
          "Could not locate the repository root (settings.gradle.kts) from "
              + System.getProperty("user.dir"));
    }
    return dir;
  }
}

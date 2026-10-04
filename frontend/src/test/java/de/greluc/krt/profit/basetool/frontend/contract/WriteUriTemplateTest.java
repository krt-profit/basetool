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

package de.greluc.krt.profit.basetool.frontend.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Keeps every {@code BackendApiClient} write verb on a URI template: a runtime value goes in as a
 * template variable, which the {@code WebClient} encodes, never concatenated into the path or query
 * (REQ-SEC-051, plan F1).
 */
class WriteUriTemplateTest {

  /** The write-verb call sites when the guard was introduced; fewer means the scan broke. */
  private static final int MIN_WRITE_SITES = 344;

  /**
   * The methods that still concatenate a runtime value into a write URI, each with the reason it is
   * safe. The set must match the scan exactly.
   */
  private static final Map<String, String> REVIEWED =
      Map.of(
          "AdminUexPageController#dispatchOverride",
          "the entity kind is narrowed to ALLOWED_LOADING_DOCK_KINDS before it is concatenated",
          "AdminUexPageController#dispatchOverrideAjax",
          "the entity kind is narrowed to ALLOWED_LOADING_DOCK_KINDS before it is concatenated");

  /** A source with one templated and one concatenating write per verb shape. */
  private static final String FIXTURE_SOURCE =
      """
      package fixture;

      class Fixture {
        private static final String BASE = "/api/v1/missions";
        private BackendApiClient backendApiClient;

        void templated(UUID id) {
          backendApiClient.put(BASE + "/{id}", null, Void.class, id);
          backendApiClient.delete("/api/v1/missions/{id}?version={v}", Void.class, id, 3L);
          backendApiClient.post(BASE + "/sync", null, Void.class);
        }

        void concatenated(UUID id) {
          backendApiClient.patch(BASE + "/" + id + "/core", null, Void.class);
        }

        void helper(UUID id) {
          send("/api/v1/missions/" + id);
        }

        private void send(String uri) {
          backendApiClient.delete(uri, Void.class);
        }
      }
      """;

  @Test
  void everyWriteCallPassesItsRuntimeValuesAsTemplateVariables() {
    BackendCallScanner.Result scan = BackendCallScanner.scanDirectory(frontendMainSources());

    assertThat(scan.writeSites())
        .as("fewer write-verb call sites than when the guard was introduced; the scan is broken")
        .isGreaterThanOrEqualTo(MIN_WRITE_SITES);
    assertThat(scan.concatenatedWrites())
        .as(
            "write calls that concatenate a runtime value into the backend URI; pass it as a URI"
                + " template variable instead, or review it here with the reason it is safe")
        .containsExactlyInAnyOrderElementsOf(REVIEWED.keySet());
  }

  @Test
  void aConcatenatedValueIsReportedAndATemplateVariableIsNot() {
    BackendCallScanner.Result scan =
        BackendCallScanner.scanSources(Map.of("Fixture.java", FIXTURE_SOURCE));

    assertThat(scan.writeSites()).isEqualTo(5);
    assertThat(scan.concatenatedWrites())
        .containsExactlyInAnyOrder("Fixture#concatenated", "Fixture#send");
  }

  /**
   * Locates the frontend's main Java sources from either the module or the repository root.
   *
   * @return the source root
   */
  private static Path frontendMainSources() {
    Path relative = Paths.get("src", "main", "java");
    if (Files.isDirectory(relative.resolve("de"))
        && Files.exists(Paths.get("src", "main", "resources", "templates"))) {
      return relative;
    }
    return Paths.get("frontend").resolve(relative);
  }
}

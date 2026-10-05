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
 * Keeps every {@code BackendApiClient} read and every {@code execute(…)} request on a URI template:
 * a runtime value goes in as a template variable, which the {@code WebClient} encodes, never
 * concatenated into the path or query (REQ-SEC-051, plan F3). With {@link WriteUriTemplateTest}
 * this covers every verb.
 */
class ReadUriTemplateTest {

  /** The {@code get} call sites when the guard was introduced; fewer means the scan broke. */
  private static final int MIN_READ_SITES = 250;

  /** The {@code execute(…)} call sites when the guard was introduced. */
  private static final int MIN_EXECUTE_SITES = 17;

  /** A source with templated and concatenating reads and executes. */
  private static final String FIXTURE_SOURCE =
      """
      package fixture;

      class Fixture {
        private static final String BASE = "/api/v1/missions";
        private BackendApiClient backendApiClient;

        void templated(UUID id, String q) {
          backendApiClient.get(BASE + "/{id}", Object.class, id);
          backendApiClient.get("/api/v1/missions?search={q}&size=20", TYPE, q);
          backendApiClient.get(BASE + "/active", Object.class);
          StringBuilder uri = new StringBuilder(BASE).append("?page={page}");
          uri.append("&q={q}");
          backendApiClient.get(uri.toString(), TYPE, 0, q);
          backendApiClient.execute(
              HttpMethod.GET,
              BASE + "/{id}/report",
              webClient -> webClient.get().uri(BASE + "/{id}/report", id),
              spec -> spec.bodyToMono(byte[].class));
          String chained =
              UriComponentsBuilder.fromPath(BASE + "/{id}/steps")
                  .queryParam("a", 1)
                  .queryParam("b", 2)
                  .queryParam("c", 3)
                  .queryParam("d", 4)
                  .queryParam("e", 5)
                  .queryParam("f", 6)
                  .queryParam("g", 7)
                  .queryParam("h", 8)
                  .queryParam("i", 9)
                  .encode()
                  .build()
                  .toUriString();
          backendApiClient.get(chained, TYPE, id);
        }

        void concatenated(UUID id) {
          backendApiClient.get(BASE + "/" + id + "/crew", Object.class);
        }

        void conditional(String domain) {
          String area = domain != null ? domain : "BANK";
          backendApiClient.get("/api/v1/audit/" + area, Object.class);
        }

        void query(String q) {
          backendApiClient.get(BASE + "?search=" + q, TYPE);
        }

        void report(UUID id) {
          backendApiClient.execute(
              HttpMethod.GET,
              BASE + "/" + id + "/report",
              webClient -> webClient.get().uri(BASE + "/" + id + "/report"),
              spec -> spec.bodyToMono(byte[].class));
        }
      }
      """;

  @Test
  void everyReadCallPassesItsRuntimeValuesAsTemplateVariables() {
    BackendCallScanner.Result scan = BackendCallScanner.scanDirectory(frontendMainSources());

    assertThat(scan.readSites())
        .as("fewer get call sites than when the guard was introduced; the scan is broken")
        .isGreaterThanOrEqualTo(MIN_READ_SITES);
    assertThat(scan.concatenatedReads())
        .as(
            "get calls that concatenate a runtime value into the backend URI; pass it as a URI"
                + " template variable instead")
        .isEmpty();
  }

  @Test
  void everyExecuteCallPassesItsRuntimeValuesAsTemplateVariables() {
    BackendCallScanner.Result scan = BackendCallScanner.scanDirectory(frontendMainSources());

    assertThat(scan.executeSites())
        .as("fewer execute call sites than when the guard was introduced; the scan is broken")
        .isGreaterThanOrEqualTo(MIN_EXECUTE_SITES);
    assertThat(scan.concatenatedExecutes())
        .as(
            "execute calls whose request concatenates a runtime value into the backend URI; pass"
                + " it as a URI template variable instead")
        .isEmpty();
  }

  @Test
  void aConcatenatedValueIsReportedAndATemplateVariableIsNot() {
    BackendCallScanner.Result scan =
        BackendCallScanner.scanSources(Map.of("Fixture.java", FIXTURE_SOURCE));

    assertThat(scan.readSites()).isEqualTo(8);
    assertThat(scan.concatenatedReads())
        .containsExactlyInAnyOrder("Fixture#concatenated", "Fixture#query", "Fixture#conditional");
    assertThat(scan.executeSites()).isEqualTo(2);
    assertThat(scan.concatenatedExecutes()).containsExactly("Fixture#report");
    assertThat(scan.calls())
        .extracting(c -> c.verb() + " " + BackendCallScanner.display(c.template()))
        .contains("GET /api/v1/missions/{}/steps");
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

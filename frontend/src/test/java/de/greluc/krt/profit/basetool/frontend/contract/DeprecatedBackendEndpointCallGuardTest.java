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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Fails the build when the web frontend calls a backend operation marked {@code deprecated} in the
 * committed {@code openapi.json}, except for the reviewed relays in {@link #DEPRECATED_RELAYS}.
 *
 * <p>The call sites come from {@link BackendCallScanner}, which also backs the existence guard
 * (REQ-FE-028), so both see the same calls.
 */
class DeprecatedBackendEndpointCallGuardTest {

  /** Fewer resolved calls than this means the scanner broke, not that the frontend got smaller. */
  private static final int MIN_RESOLVED_CALL_SITES = 544;

  /**
   * Deprecated operations the frontend still relays on purpose, keyed {@code VERB template}, each
   * with the reason; an entry no call uses any more fails the test.
   */
  private static final Map<String, String> DEPRECATED_RELAYS =
      Map.of(
          "POST /api/v1/hangar/import/fleetview",
          "HangarImportProxyController#importFleetview relays the deprecated alias until its"
              + " sunset; the frontend route goes with the backend operation");

  @Test
  void theFrontendCallsNoDeprecatedBackendOperation() {
    BackendCallScanner.Result scan = BackendCallScanner.scanDirectory(frontendMainSources());
    BackendOperations deprecated =
        BackendOperations.of(
            BackendOperations.committedDocument(), op -> op.path("deprecated").asBoolean(false));

    assertThat(scan.callSites())
        .as("the scan resolved too few backend calls; the scanner is broken")
        .isGreaterThanOrEqualTo(MIN_RESOLVED_CALL_SITES);

    List<BackendCallScanner.Call> calls =
        scan.calls().stream().filter(c -> deprecated.exists(c.verb(), c.template())).toList();
    List<String> offenders =
        calls.stream()
            .filter(c -> !DEPRECATED_RELAYS.containsKey(signature(c)))
            .map(BackendCallScanner.Call::describe)
            .toList();
    assertThat(offenders)
        .as(
            "the frontend calls backend operations marked deprecated in openapi.json; move each to"
                + " the replacement its @ApiDeprecation names before the sunset removes it")
        .isEmpty();
    assertThat(calls.stream().map(DeprecatedBackendEndpointCallGuardTest::signature).toList())
        .as("every reviewed deprecated relay is still in use; remove the stale entries")
        .containsAll(DEPRECATED_RELAYS.keySet());
  }

  @Test
  void aCallToADeprecatedOperationIsFound() {
    String source =
        """
        class Sample {
          void call(UUID missionUuid, UUID userUuid, UUID id) {
            backendApiClient.put(
                "/api/v1/missions/" + missionUuid + "/owner/" + userUuid, null, Void.class);
            backendApiClient.get("/api/v1/missions/" + id + "/units?size=1000", TYPE);
          }
        }
        """;
    JsonNode document =
        JsonMapper.builder()
            .build()
            .readTree(
                "{\"paths\":{\"/api/v1/missions/{id}/owner/{userId}\":{\"put\":"
                    + "{\"deprecated\":true},\"get\":{}},"
                    + "\"/api/v1/missions/{id}/units\":{\"get\":{}}}}");
    BackendOperations deprecated =
        BackendOperations.of(document, op -> op.path("deprecated").asBoolean(false));

    List<String> found =
        BackendCallScanner.scanSources(Map.of("Sample.java", source)).calls().stream()
            .filter(c -> deprecated.exists(c.verb(), c.template()))
            .map(DeprecatedBackendEndpointCallGuardTest::signature)
            .toList();

    assertThat(found).containsExactly("PUT /api/v1/missions/{}/owner/{}");
  }

  /**
   * Renders a call as {@code VERB template} with every runtime part written {@code {}}.
   *
   * @param call the call
   * @return the signature
   */
  private static String signature(BackendCallScanner.Call call) {
    return call.verb() + " " + BackendCallScanner.display(call.template());
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

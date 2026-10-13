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

package de.greluc.krt.profit.basetool.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.platform.api.RetiredOperations;
import de.greluc.krt.profit.basetool.backend.platform.internal.RetiredOperationsConfig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.PathContainer;
import tools.jackson.databind.JsonNode;

/**
 * Ties the committed list of the current app build to the API it calls: every call is served by the
 * committed document at its new path, and none is on the retired-operation list (REQ-API-016,
 * REQ-API-020).
 */
class CommittedAppCallListTest {

  /** The build whose list is committed as the current one; the release floor equals it. */
  private static final int CURRENT_BUILD = 20;

  /** The calls of the current build when its list was committed. */
  private static final int CURRENT_CALLS = 244;

  /**
   * Lists the calls the document does not serve as an operation.
   *
   * @param release the app list
   * @param document the OpenAPI document
   * @return one line per missing operation
   */
  static @NotNull @Unmodifiable List<String> notServed(
      @NotNull AppCallList.Release release, @NotNull JsonNode document) {
    List<String> missing = new ArrayList<>();
    for (AppCallList.Call call : release.calls()) {
      if (OpenApiWalk.operation(document, call.path(), call.method()).isMissingNode()) {
        missing.add(call.key());
      }
    }
    return List.copyOf(missing);
  }

  /**
   * Lists the calls a retired entry would answer with {@code 410}.
   *
   * @param release the app list
   * @param retired the retired operations
   * @return one line per call on the retired list
   */
  static @NotNull @Unmodifiable List<String> retiredCalls(
      @NotNull AppCallList.Release release, @NotNull RetiredOperations retired) {
    List<String> hits = new ArrayList<>();
    for (AppCallList.Call call : release.calls()) {
      String concrete = call.path().replaceAll("\\{[^}]+}", "x");
      if (retired.match(call.method(), PathContainer.parsePath(concrete)).isPresent()) {
        hits.add(call.key());
      }
    }
    return List.copyOf(hits);
  }

  /**
   * Loads the committed list of the current build.
   *
   * @return the list named {@code 20}
   * @throws IOException if the lists cannot be read
   */
  private static AppCallList.Release current() throws IOException {
    return AppCallList.load(
            EdgeAdmissionTest.repositoryRoot().resolve("backend/src/test/resources/api/app-calls"))
        .stream()
        .filter(release -> release.versionCode() == CURRENT_BUILD)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no app call list " + CURRENT_BUILD + ".txt"));
  }

  @Test
  @DisplayName("the list of the current app build is the app's own, with every call it makes")
  void theCurrentListIsCommitted() throws IOException {
    assertThat(current().calls()).hasSize(CURRENT_CALLS);
  }

  @Test
  @DisplayName("every call of the current app build is served by the document at its new path")
  void everyCallIsServed() throws IOException {
    assertThat(notServed(current(), CommittedOpenApi.published()))
        .as("the app calls an operation the committed document does not serve")
        .isEmpty();
  }

  @Test
  @DisplayName("no call of the current app build is on the retired-operation list")
  void noCallIsRetired() throws IOException {
    RetiredOperations retired = new RetiredOperationsConfig().retiredOperations();
    assertThat(retired.isEmpty()).as("the retired list is committed").isFalse();
    assertThat(retiredCalls(current(), retired))
        .as("the app calls an operation the server answers 410 APP_UPDATE_REQUIRED")
        .isEmpty();
  }

  @Test
  @DisplayName("control: a planted old path is not served and a planted retired path is found")
  void theChecksCanFail() {
    AppCallList.Release planted =
        AppCallList.parse(
            "19",
            List.of(
                "GET /api/v1/material-requests q=- f=- s=Planted.old.00000000",
                "POST /api/v1/missions/{id}/steps/slim q=- f=- s=Planted.old.00000001"));
    assertThat(notServed(planted, CommittedOpenApi.published()))
        .containsExactly("GET /api/v1/material-requests", "POST /api/v1/missions/{id}/steps/slim");
    RetiredOperations retired =
        RetiredOperations.parse(
            List.of("GET /api/v1/material-requests", "POST /api/v1/missions/{id}/steps/slim"));
    assertThat(retiredCalls(planted, retired))
        .containsExactlyInAnyOrder(
            "GET /api/v1/material-requests", "POST /api/v1/missions/{id}/steps/slim");
  }
}

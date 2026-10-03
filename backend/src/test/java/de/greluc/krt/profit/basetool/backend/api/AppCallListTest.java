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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Proves the app call list coverage and its agreement with the ledger able to fail, on planted
 * lists (REQ-API-016, REQ-API-017).
 */
class AppCallListTest {

  /** A two-call list in the app's published format. */
  private static final List<String> LIST =
      List.of(
          "GET /api/v1/things q=page,q f=content,id s=ThingRepository.page.1a2b3c4d",
          "DELETE /api/v1/things/{id} q=- f=- s=ThingRepository.delete.5e6f7a8b");

  /** A list parses into its calls, with names split and {@code -} read as none. */
  @Test
  @DisplayName("a published list parses into its calls")
  void aListParses() {
    AppCallList.Release release = AppCallList.parse("18", LIST);

    assertThat(release.versionCode()).isEqualTo(18);
    assertThat(release.calls())
        .containsExactly(
            new AppCallList.Call(
                "GET", "/api/v1/things", Set.of("page", "q"), Set.of("content", "id")),
            new AppCallList.Call("DELETE", "/api/v1/things/{id}", Set.of(), Set.of()));
    assertThat(AppCallList.parse("unreleased", LIST).versionCode())
        .isEqualTo(AppCallList.UNRELEASED);
  }

  /**
   * Every malformed line fails the parse, naming its line.
   *
   * @param line the planted line
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "GET /api/v1/things q=page",
        "FETCH /api/v1/things q=- f=- s=x",
        "GET things q=- f=- s=x",
        "GET /api/v1/things f=- s=x t=y",
        "GET /api/v1/things q=- q=page f=- s=x",
        "GET /api/v1/things q=page,,q f=- s=x"
      })
  @DisplayName("a malformed list line fails the parse")
  void aMalformedLineFails(String line) {
    assertThatThrownBy(() -> AppCallList.parse("18", List.of("", line)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("line 2");
  }

  /** A list named neither by a versionCode nor {@code unreleased}, or listing twice, fails. */
  @Test
  @DisplayName("a misnamed list or a repeated call fails the parse")
  void aMisnamedListOrARepeatedCallFails() {
    assertThatThrownBy(() -> AppCallList.parse("v0.4.0", LIST))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("versionCode");
    assertThatThrownBy(() -> AppCallList.parse("18", List.of(LIST.get(0), LIST.get(0))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("second time");
    assertThatThrownBy(() -> AppCallList.parse("18", List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no call");
  }

  /** An unfrozen call and an unfrozen query parameter are both reported. */
  @Test
  @DisplayName("a call or a query parameter the frozen set lacks is uncovered")
  void anUncoveredCallIsReported() {
    List<AppCallList.Release> releases = List.of(AppCallList.parse("18", LIST));

    assertThat(
            AppCallList.uncovered(
                releases,
                Map.of(
                    "GET /api/v1/things", Set.of("page", "q"),
                    "DELETE /api/v1/things/{id}", Set.of())))
        .isEmpty();
    assertThat(AppCallList.uncovered(releases, Map.of("GET /api/v1/things", Set.of("page", "q"))))
        .containsExactly("18: DELETE /api/v1/things/{id} is not in the frozen set");
    assertThat(
            AppCallList.uncovered(
                releases,
                Map.of(
                    "GET /api/v1/things", Set.of("page"), "DELETE /api/v1/things/{id}", Set.of())))
        .containsExactly(
            "18: GET /api/v1/things sends query parameters the frozen set does not freeze: [q]");
  }

  /** A list older than an absorbing build, and a newer list calling a gone operation, conflict. */
  @Test
  @DisplayName("the ledger walls off older lists and forbids gone calls in newer ones")
  void theLedgerConflictsAreReported() {
    List<AppCallList.Release> releases =
        List.of(AppCallList.parse("17", LIST), AppCallList.parse("unreleased", LIST));
    List<DeclaredBreaks.Entry> ledger =
        DeclaredBreaks.parse(List.of("DELETE /api/v1/things/{id} - 18"));

    assertThat(AppCallList.conflictsWithLedger(releases, ledger))
        .hasSize(2)
        .anySatisfy(conflict -> assertThat(conflict).startsWith("17 is older than build 18"))
        .anySatisfy(
            conflict ->
                assertThat(conflict)
                    .startsWith("unreleased still calls DELETE /api/v1/things/{id}"));

    List<AppCallList.Release> absorbed = List.of(AppCallList.parse("18", List.of(LIST.get(0))));
    assertThat(AppCallList.conflictsWithLedger(absorbed, ledger)).isEmpty();
    assertThat(AppCallList.conflictsWithLedger(releases, List.of())).isEmpty();
  }

  /**
   * A directory loads sorted by versionCode with the unreleased build last, and a stray file fails.
   *
   * @param directory an empty directory for the fixture
   * @throws IOException if the fixture cannot be written
   */
  @Test
  @DisplayName("a directory of lists loads in build order and refuses a stray file")
  void aDirectoryLoadsInBuildOrder(@TempDir Path directory) throws IOException {
    Files.write(directory.resolve("unreleased.txt"), LIST);
    Files.write(directory.resolve("19.txt"), LIST);
    Files.write(directory.resolve("18.txt"), LIST);

    assertThat(AppCallList.load(directory))
        .extracting(AppCallList.Release::name)
        .containsExactly("18", "19", "unreleased");

    Files.writeString(directory.resolve("README.md"), "x");
    assertThatThrownBy(() -> AppCallList.load(directory))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("README.md");
  }
}

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

package de.greluc.krt.profit.basetool.frontend.support;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assumptions.assumeThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Proves the golden-file comparison able to fail on an added, a removed and a changed line. */
class GoldenFileTest {

  /** A committed golden file the comparison is exercised against; it is never written here. */
  private static final String RESOURCE = "security/route-gate-snapshot.txt";

  private List<String> committed;

  @BeforeEach
  void readCommitted() throws IOException {
    assumeThat(Boolean.getBoolean(GoldenFile.UPDATE_PROPERTY))
        .as("rewrite mode would overwrite the golden file with the mutated lines")
        .isFalse();
    committed =
        Files.readAllLines(
            Path.of("src", "test", "resources").resolve(RESOURCE), StandardCharsets.UTF_8);
  }

  @Test
  void anAddedLineFails() {
    List<String> actual = new ArrayList<>(committed);
    actual.add("/planted GET -> PlantedController#planted gate=none layout=no");

    assertThatThrownBy(() -> GoldenFile.assertMatches(RESOURCE, actual, "fixture"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("Only in the code (1)")
        .hasMessageContaining("PlantedController#planted");
  }

  @Test
  void aRemovedLineFails() {
    List<String> actual = new ArrayList<>(committed);
    String removed = actual.removeFirst();

    assertThatThrownBy(() -> GoldenFile.assertMatches(RESOURCE, actual, "fixture"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("Only in the golden file (1)")
        .hasMessageContaining(removed);
  }

  @Test
  void aWeakenedGateFails() {
    List<String> actual =
        committed.stream()
            .map(line -> line.replace("gate=class:hasRole('ADMIN')", "gate=none"))
            .toList();

    assertThatThrownBy(() -> GoldenFile.assertMatches(RESOURCE, actual, "fixture"))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("gate=none");
  }
}

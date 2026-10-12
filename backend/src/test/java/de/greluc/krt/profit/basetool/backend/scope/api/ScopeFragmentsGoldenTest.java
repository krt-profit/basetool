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

package de.greluc.krt.profit.basetool.backend.scope.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the six JPQL scope fragments of REQ-DATA-010 byte for byte: the fragment of every scoped
 * aggregate is the text recorded in {@code scope/scope-fragments.txt}, wherever its owning module
 * keeps the constant.
 *
 * <p>A fragment is spliced into {@code @Query} values, so a changed character is a changed query. A
 * deliberate change rewrites the golden file in the same commit.
 */
class ScopeFragmentsGoldenTest {

  private static final String BASE = "de.greluc.krt.profit.basetool.backend.";

  private static final List<String[]> FRAGMENTS =
      List.of(
          new String[] {"MISSION_SCOPE_PREDICATE", BASE + "mission.api.MissionScope", "PREDICATE"},
          new String[] {"SHIP_SCOPE_TRIPLE", BASE + "hangar.api.ShipScope", "TRIPLE"},
          new String[] {
            "INVENTORY_ITEM_SCOPE_TRIPLE", BASE + "inventory.api.InventoryScope", "TRIPLE"
          },
          new String[] {
            "JOB_ORDER_SCOPE_PREDICATE", BASE + "joborder.api.JobOrderScope", "PREDICATE"
          },
          new String[] {
            "REFINERY_ORDER_SCOPE_TRIPLE",
            BASE + "refinery.internal.RefineryAccessPolicy",
            "REFINERY_ORDER_SCOPE_TRIPLE"
          },
          new String[] {
            "OPERATION_SCOPE_PREDICATE",
            BASE + "operation.internal.OperationAccessPolicy",
            "OPERATION_SCOPE_PREDICATE"
          });

  @Test
  void everyFragmentIsTheRecordedText() throws Exception {
    String actual = render();
    Files.writeString(Path.of("build/scope-fragments.actual.txt"), actual);
    String expected;
    try (var in = getClass().getResourceAsStream("/scope/scope-fragments.txt")) {
      assertThat(in).isNotNull();
      expected = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    assertThat(actual).isEqualTo(expected);
  }

  private static String render() throws ReflectiveOperationException, IOException {
    List<String> blocks = new ArrayList<>();
    for (String[] fragment : FRAGMENTS) {
      Field field = Class.forName(fragment[1]).getDeclaredField(fragment[2]);
      field.setAccessible(true);
      blocks.add("=== " + fragment[0] + "\n" + field.get(null) + "=== end\n");
    }
    return String.join("", blocks);
  }
}

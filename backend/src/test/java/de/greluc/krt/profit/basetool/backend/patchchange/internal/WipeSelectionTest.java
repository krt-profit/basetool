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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockWipeRow;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * How the wipe groups and the exceptions select stock rows (REQ-PATCH-010, REQ-PATCH-011): a
 * selected group wipes its rows, a keeping exception saves its entry's rows, a wiping exception
 * wipes them outside the selected groups, and every group is tallied whether selected or not.
 */
class WipeSelectionTest {

  private static final UUID ADA = UUID.randomUUID();
  private static final UUID BEA = UUID.randomUUID();
  private static final UUID MEDPEN = UUID.randomUUID();
  private static final UUID RIFLE = UUID.randomUUID();
  private static final UUID QUANTANIUM = UUID.randomUUID();
  private static final UUID LARANITE = UUID.randomUUID();

  private final StockWipeRow medpenOfAda = item(ADA, MEDPEN, "FPS_Consumable");
  private final StockWipeRow medpenOfBea = item(BEA, MEDPEN, "FPS_Consumable");
  private final StockWipeRow rifleOfAda = item(ADA, RIFLE, "WeaponPersonal");
  private final StockWipeRow quantaniumOfBea = material(BEA, QUANTANIUM, MaterialType.RAW);
  private final StockWipeRow laraniteOfAda = material(ADA, LARANITE, MaterialType.REFINED);
  private final List<StockWipeRow> rows =
      List.of(medpenOfAda, medpenOfBea, rifleOfAda, quantaniumOfBea, laraniteOfAda);

  @Test
  void theSelectedGroupsWipeTheirRows() {
    WipeSelection selection =
        WipeSelection.of(
            rows, options(Set.of(WipeGroup.CONSUMABLES, WipeGroup.MATERIALS_RAW), Set.of()));

    assertThat(selection.wipedRowIds())
        .containsExactlyInAnyOrder(medpenOfAda.id(), medpenOfBea.id(), quantaniumOfBea.id());
    assertThat(selection.wipedRowsByGroup())
        .isEqualTo(
            Map.of(
                "CONSUMABLES", Set.of(medpenOfAda.id(), medpenOfBea.id()),
                "MATERIALS_RAW", Set.of(quantaniumOfBea.id())));
    assertThat(selection.wipedMembers()).isEqualTo(2);
  }

  @Test
  void aKeepingExceptionSavesItsEntryInASelectedGroup() {
    WipeException keepQuantanium =
        new WipeException(WipeExceptionTarget.MATERIAL, QUANTANIUM, WipeExceptionMode.KEEP);

    WipeSelection selection =
        WipeSelection.of(rows, options(Set.of(WipeGroup.MATERIALS_RAW), Set.of(keepQuantanium)));

    assertThat(selection.wipedRowIds()).isEmpty();
    assertThat(selection.exceptionTally(keepQuantanium)).isEqualTo(new WipeSelection.Tally(1, 1));
    assertThat(selection.groupTally(WipeGroup.MATERIALS_RAW))
        .isEqualTo(new WipeSelection.Tally(0, 0));
  }

  @Test
  void aWipingExceptionWipesItsEntryOutsideTheSelectedGroups() {
    WipeException wipeRifle =
        new WipeException(WipeExceptionTarget.GAME_ITEM, RIFLE, WipeExceptionMode.WIPE);

    WipeSelection selection =
        WipeSelection.of(rows, options(Set.of(WipeGroup.CONSUMABLES), Set.of(wipeRifle)));

    assertThat(selection.wipedRowsByGroup())
        .isEqualTo(
            Map.of(
                "CONSUMABLES",
                Set.of(medpenOfAda.id(), medpenOfBea.id()),
                WipeSelection.EXCEPTIONS,
                Set.of(rifleOfAda.id())));
    assertThat(selection.exceptionTally(wipeRifle)).isEqualTo(new WipeSelection.Tally(1, 1));
    assertThat(selection.groupTally(WipeGroup.FPS_EQUIPMENT))
        .isEqualTo(new WipeSelection.Tally(0, 0));
  }

  @Test
  void everyGroupIsTalliedWhetherSelectedOrNot() {
    WipeSelection selection = WipeSelection.of(rows, options(Set.of(), Set.of()));

    assertThat(selection.wipedRowIds()).isEmpty();
    assertThat(selection.groupTally(WipeGroup.CONSUMABLES))
        .isEqualTo(new WipeSelection.Tally(2, 2));
    assertThat(selection.groupTally(WipeGroup.MATERIALS_REFINED))
        .isEqualTo(new WipeSelection.Tally(1, 1));
    assertThat(selection.groupTally(WipeGroup.OTHER_ITEMS))
        .isEqualTo(new WipeSelection.Tally(0, 0));
  }

  @Test
  void anExceptionWithoutRowsTalliesNothing() {
    WipeException keepUnknown =
        new WipeException(WipeExceptionTarget.GAME_ITEM, UUID.randomUUID(), WipeExceptionMode.KEEP);

    WipeSelection selection =
        WipeSelection.of(rows, options(Set.of(WipeGroup.CONSUMABLES), Set.of(keepUnknown)));

    assertThat(selection.exceptionTally(keepUnknown)).isEqualTo(new WipeSelection.Tally(0, 0));
  }

  private static PatchChangeOptions options(Set<WipeGroup> groups, Set<WipeException> exceptions) {
    return new PatchChangeOptions(groups, exceptions, false, false, false);
  }

  private static StockWipeRow item(UUID holder, UUID gameItem, String type) {
    return new StockWipeRow(
        UUID.randomUUID(), holder, null, null, gameItem, type, null, null, GameItemKind.GENERIC);
  }

  private static StockWipeRow material(UUID holder, UUID material, MaterialType type) {
    return new StockWipeRow(
        UUID.randomUUID(), holder, material, type, null, null, null, null, null);
  }
}

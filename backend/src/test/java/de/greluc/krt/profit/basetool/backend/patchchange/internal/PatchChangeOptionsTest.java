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

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The options' wire form and when they wipe stock (REQ-PATCH-002, REQ-PATCH-011): a body without
 * exceptions reads as none, and only a stock group or a wiping exception runs the stock wipe.
 */
class PatchChangeOptionsTest {

  private final JsonMapper mapper = JsonMapper.builder().build();

  @Test
  void aBodyWithoutExceptionsReadsAsNone() {
    PatchChangeOptions options =
        mapper.readValue(
            """
            {"wipeGroups": ["CONSUMABLES"], "cancelRefineryOrders": true,
             "currencyWipe": false, "homeLocationPrompt": true}
            """,
            PatchChangeOptions.class);

    assertThat(options)
        .isEqualTo(new PatchChangeOptions(Set.of(WipeGroup.CONSUMABLES), true, false, true));
  }

  @Test
  void aBodyCarriesItsExceptions() {
    UUID gold = UUID.fromString("00000000-0000-0000-0000-00000000a001");

    PatchChangeOptions options =
        mapper.readValue(
            """
            {"wipeGroups": [], "wipeExceptions": [
              {"target": "MATERIAL", "id": "00000000-0000-0000-0000-00000000a001", "mode": "KEEP"}],
             "cancelRefineryOrders": false, "currencyWipe": false, "homeLocationPrompt": false}
            """,
            PatchChangeOptions.class);

    assertThat(options.wipeExceptions())
        .containsExactly(
            new WipeException(WipeExceptionTarget.MATERIAL, gold, WipeExceptionMode.KEEP));
  }

  @Test
  void onlyAStockGroupOrAWipingExceptionWipesStock() {
    UUID item = UUID.randomUUID();
    WipeException keep =
        new WipeException(WipeExceptionTarget.GAME_ITEM, item, WipeExceptionMode.KEEP);
    WipeException wipe =
        new WipeException(WipeExceptionTarget.GAME_ITEM, item, WipeExceptionMode.WIPE);

    assertThat(
            new PatchChangeOptions(Set.of(WipeGroup.IN_GAME_SHIPS), true, true, true).wipesStock())
        .isFalse();
    assertThat(new PatchChangeOptions(Set.of(), Set.of(keep), true, true, true).wipesStock())
        .isFalse();
    assertThat(new PatchChangeOptions(Set.of(), Set.of(wipe), true, true, true).wipesStock())
        .isTrue();
    assertThat(PatchChangeOptions.presetFor(PatchKind.REBUILD).wipesStock()).isTrue();
  }
}

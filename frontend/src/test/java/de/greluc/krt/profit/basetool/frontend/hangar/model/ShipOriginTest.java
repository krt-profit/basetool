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

package de.greluc.krt.profit.basetool.frontend.hangar.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The lenient reading of a ship's origin (REQ-HANGAR-017): a filter takes any known origin, a form
 * choice only the two a member can pick, and a crafted value reads as none.
 */
class ShipOriginTest {

  @Test
  void aFilterReadsEveryKnownOriginAndNothingElse() {
    assertThat(ShipOrigin.parse("UNKNOWN")).isEqualTo(ShipOrigin.UNKNOWN);
    assertThat(ShipOrigin.parse("in_game")).isNull();
    assertThat(ShipOrigin.parse(null)).isNull();
  }

  @Test
  void aFormChoiceIsPledgeOrInGameOnly() {
    assertThat(ShipOrigin.choiceOf("PLEDGE")).isEqualTo(ShipOrigin.PLEDGE);
    assertThat(ShipOrigin.choiceOf("IN_GAME")).isEqualTo(ShipOrigin.IN_GAME);
    assertThat(ShipOrigin.choiceOf("UNKNOWN")).isNull();
    assertThat(ShipOrigin.choiceOf("RENTED")).isNull();
    assertThat(ShipOrigin.choiceOf(null)).isNull();
  }

  @Test
  void aPayloadCarriesTheConstantsName() {
    assertThat(ShipOrigin.nameOf(ShipOrigin.IN_GAME)).isEqualTo("IN_GAME");
    assertThat(ShipOrigin.nameOf(null)).isNull();
  }
}

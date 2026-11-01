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

package de.greluc.krt.profit.basetool.frontend.orgchart.web;

import de.greluc.krt.profit.basetool.frontend.leadership.model.AreaLeadershipDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.BereichChartDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.CommandChartDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.OlChartDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.OrgChartDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.OrgChartNodeDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.SpecialCommandChartDto;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.SquadronChartDto;
import java.util.List;
import java.util.UUID;

/** A small org chart for the quick-access contributor tests: one of every tier and rank shape. */
final class OrgChartFixture {

  static final UUID OL_ID = id(1);
  static final UUID BEREICH_ID = id(2);
  static final UUID SQUADRON_ID = id(3);
  static final UUID SK_ID = id(4);
  static final UUID UNGROUPED_SQUADRON_ID = id(5);
  static final UUID ALICE = id(11);
  static final UUID BOB = id(12);
  static final UUID CARA = id(13);
  static final UUID DORA = id(14);
  static final UUID ERIC = id(15);
  static final UUID FRED = id(16);
  static final UUID COMMAND_POSITION = id(21);
  static final UUID FREE_TEXT_POSITION = id(22);

  private OrgChartFixture() {}

  static UUID id(int n) {
    return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
  }

  static OrgChartNodeDto node(String type, UUID userId, String userName, String displayName) {
    return new OrgChartNodeDto(
        userId == null ? FREE_TEXT_POSITION : userId, type, userId, userName, displayName, 0, 1L);
  }

  /**
   * The chart: the OL with a Grand Admiral and a member, one Bereich with a leader, a squadron with
   * a lead, a Kommando with leader, deputy and ensign, and an SK, plus an ungrouped squadron and
   * the ungrouped area leadership.
   */
  static OrgChartDto chart() {
    CommandChartDto command =
        new CommandChartDto(
            COMMAND_POSITION,
            "Alpha Kommando",
            1L,
            0,
            null,
            DORA,
            "Dora",
            null,
            node("DEPUTY_COMMAND_LEAD", null, null, "Gustav Freitext"),
            List.of(node("ENSIGN", ERIC, "Eric", null)));
    SquadronChartDto squadron =
        new SquadronChartDto(
            SQUADRON_ID,
            "Wolf Staffel",
            "WLF",
            node("SQUADRON_LEAD", CARA, "Cara", null),
            List.of(command),
            List.of(),
            true,
            true);
    SpecialCommandChartDto sk =
        new SpecialCommandChartDto(
            SK_ID, "Bravo SK", "BRV", List.of(node("SK_COMMANDER", FRED, "Fred", null)), true);
    BereichChartDto bereich =
        new BereichChartDto(
            BEREICH_ID,
            "Flotte",
            "FLT",
            null,
            new AreaLeadershipDto(
                node("BEREICHSLEITER", BOB, "Bob", null), List.of(), List.of(), List.of()),
            List.of(squadron),
            List.of(sk));
    SquadronChartDto ungrouped =
        new SquadronChartDto(
            UNGROUPED_SQUADRON_ID,
            "Ungrouped Staffel",
            "UNG",
            null,
            List.of(),
            List.of(),
            true,
            true);
    return new OrgChartDto(
        new OlChartDto(
            OL_ID,
            "Organisationsleitung",
            "OL",
            node("OL_MEMBER", ALICE, "Alice", null),
            List.of()),
        List.of(bereich),
        new AreaLeadershipDto(null, List.of(), List.of(), List.of()),
        List.of(ungrouped),
        List.of());
  }
}

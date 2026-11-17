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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The keys a UEX location row finds its starmap object by, on the LIVE names the analysis of the
 * open link cases found (REQ-P4K-035).
 */
class StarmapCandidatesTest {

  private StarmapTreeBuilder builder;
  private UUID stanton;
  private UUID crusader;
  private UUID yela;
  private UUID daymar;
  private UUID arcCorp;

  @BeforeEach
  void setUp() {
    builder = new StarmapTreeBuilder();
    stanton = builder.add("Stanton", "Star", null);
    crusader = builder.add("Crusader", "Planet", stanton);
    yela = builder.add("Yela", "Moon", crusader);
    daymar = builder.add("Daymar", "Moon", crusader);
    arcCorp = builder.add("ArcCorp", "Planet", stanton);
  }

  private List<UUID> find(StarmapPlaceRow row, UUID... scopes) {
    return new StarmapCandidates(builder.build()).find(row, List.of(scopes));
  }

  @Test
  void aStarSystemRow_findsTheObjectOfItsNameAndType_withoutAScope() {
    StarmapPlaceRow system =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.STAR_SYSTEM, "Stanton").build();

    assertThat(find(system)).containsExactly(stanton);
  }

  @Test
  void anObjectBelowAFurtherLinkedParent_isFound_whenTheNearestHoldsNone() {
    UUID station =
        builder.add("ARC-L1 Wide Forest Station", "Manmade_VisibleOnInteraction", stanton);
    StarmapPlaceRow row =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.SPACE_STATION, "ARC-L1 Wide Forest Station")
            .planet("ArcCorp")
            .build();

    assertThat(find(row, arcCorp, stanton)).containsExactly(station);
  }

  @Test
  void aNameWithoutItsSpaces_findsTheObject() {
    UUID area18 = builder.add("Area18", "LandingZone", arcCorp);
    StarmapPlaceRow city = StarmapPlaceRowBuilder.row(StarmapPlaceKind.CITY, "Area 18").build();

    assertThat(find(city, arcCorp, stanton)).containsExactly(area18);
  }

  @Test
  void aNameWithoutItsStarSystemGroup_findsTheObject() {
    UUID gateway = builder.add("Pyro Gateway", "Manmade", stanton);
    StarmapPlaceRow station =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.SPACE_STATION, "Pyro Gateway (Stanton)")
            .build();

    assertThat(find(station, stanton)).containsExactly(gateway);
  }

  @Test
  void theUexNickname_findsTheObject() {
    UUID grimHex = builder.add("Grim HEX", "Manmade", yela);
    StarmapPlaceRow station =
        StarmapPlaceRowBuilder.row(
                StarmapPlaceKind.SPACE_STATION, "Green Imperial Housing Exchange")
            .nickname("Grim HEX")
            .moon("Yela")
            .build();

    assertThat(find(station, yela, crusader, stanton)).containsExactly(grimHex);
  }

  @Test
  void theOnlyObjectOfTheNameInTheStarSystem_isFound_whateverItsType() {
    UUID delamar = builder.add("Delamar", "Moon", stanton);
    builder.add("Delamar", "Moon", builder.add("Nyx", "Star", null));
    StarmapPlaceRow planet = StarmapPlaceRowBuilder.row(StarmapPlaceKind.PLANET, "Delamar").build();

    assertThat(find(planet, stanton)).containsExactly(delamar);
  }

  @Test
  void ofSeveralCandidates_theOnlyPlacedOne_isFound() {
    UUID placed = builder.add("Slowburn Depot", "Outpost", daymar);
    builder.unplaced("Slowburn Depot", "Outpost", daymar);
    StarmapPlaceRow outpost =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.OUTPOST, "Slowburn Depot")
            .moon("Daymar")
            .build();

    assertThat(find(outpost, daymar, crusader, stanton)).containsExactly(placed);
  }

  @Test
  void twoPlacedCandidates_stayAmbiguous() {
    UUID first = builder.add("Mining Base #XJZ-JR2", "Asteroid", stanton);
    UUID second = builder.add("Mining Base #XJZ-JR2", "Asteroid", stanton);
    StarmapPlaceRow poi =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.POI, "Mining Base #XJZ-JR2").build();

    assertThat(find(poi, stanton)).containsExactlyInAnyOrder(first, second);
  }

  @Test
  void theLetterO_readAsZero_findsAMiningBase() {
    UUID base = builder.add("Mining Base #4R0-NVX", "Asteroid", stanton);
    StarmapPlaceRow poi =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.POI, "Mining Base #4RO-NVX").build();

    assertThat(find(poi, stanton)).containsExactly(base);
  }

  @Test
  void aNameExtendedByWholeWords_findsTheObjectBelowTheNearestLinkedParent() {
    UUID deakins = builder.add("Deakins Research Outpost", "Outpost", yela);
    builder.add("Deakins Research Outpost", "Outpost", daymar);
    StarmapPlaceRow outpost =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.OUTPOST, "Deakins Research")
            .moon("Yela")
            .build();

    assertThat(find(outpost, yela, crusader, stanton)).containsExactly(deakins);
  }

  @Test
  void aStampedObject_isNoCandidate() {
    UUID olisar = builder.add("Port Olisar", "Manmade", crusader);
    builder.object(olisar).setP4kDeletedAt(Instant.EPOCH);
    StarmapPlaceRow station =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.SPACE_STATION, "Port Olisar")
            .planet("Crusader")
            .build();

    assertThat(find(station, crusader, stanton)).isEmpty();
  }
}

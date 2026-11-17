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

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Which pass decides a UEX location row's link, and which unlinked row opens a review case
 * (REQ-P4K-035, REQ-P4K-060).
 */
class StarmapLinkRunTest {

  private StarmapTreeBuilder builder;
  private UUID stanton;
  private UUID hurston;

  @BeforeEach
  void setUp() {
    builder = new StarmapTreeBuilder();
    stanton = builder.add("Stanton", "Star", null);
    hurston = builder.add("Hurston", "Planet", stanton);
  }

  /**
   * Starts a run over the tree with Stanton recorded as linked.
   *
   * @param terminals the UEX terminal rows
   * @param storagePlaces the GUIDs of the objects a storage place sits on
   * @param planets the UEX planet rows
   * @return the run
   */
  private StarmapLinkRun run(
      List<StarmapPlaceRow> terminals, Set<UUID> storagePlaces, List<StarmapPlaceRow> planets) {
    P4kStarmapTree tree = builder.build();
    StarmapLinkRun run =
        new StarmapLinkRun(
            new StarmapCandidates(tree),
            new StarmapCaseRules(tree, terminals, storagePlaces),
            planets);
    run.record(
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.STAR_SYSTEM, "Stanton").build(), stanton);
    return run;
  }

  private StarmapLinkRun run() {
    return run(List.of(), Set.of(), List.of());
  }

  @Test
  void aTerminalNamedLikeAnObject_isNeverNameMatched_andOpensNoCase() {
    builder.add("Lorville", "LandingZone", hurston);
    StarmapPlaceRow terminal =
        StarmapPlaceRowBuilder.terminal("Lorville", "Lorville", "item").build();

    assertThat(run().decide(terminal)).isEqualTo(new StarmapLinkRun.Decision(null, null));
  }

  @Test
  void aRowOfAStarSystemThatLinksToNoObject_linksNothing_andOpensNoCase() {
    StarmapPlaceRow planet =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.PLANET, "Hurston").starSystem("Ellis").build();

    assertThat(run().decide(planet)).isEqualTo(new StarmapLinkRun.Decision(null, null));
  }

  @Test
  void anOrbitSharingItsUexIdWithAPlanet_takesThePlanetsLink() {
    StarmapPlaceRow planet =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.PLANET, "Hurston").uexId(116).build();
    StarmapLinkRun run = run(List.of(), Set.of(), List.of(planet));
    run.record(planet, hurston);
    StarmapPlaceRow orbit =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.ORBIT, "Hurston").uexId(116).build();

    assertThat(run.decide(orbit)).isEqualTo(new StarmapLinkRun.Decision(hurston, null));
  }

  @Test
  void anOrbitOfAnUnlinkedPlanet_andAnOrbitThatIsNoPlanet_openNoCase() {
    StarmapPlaceRow planet =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.PLANET, "Hurston").uexId(116).build();
    StarmapLinkRun run = run(List.of(), Set.of(), List.of(planet));
    StarmapPlaceRow twin =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.ORBIT, "Hurston").uexId(116).build();
    StarmapPlaceRow lagrange =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.ORBIT, "Hurston Lagrange Point 1")
            .uexId(334)
            .build();

    assertThat(run.decide(twin)).isEqualTo(new StarmapLinkRun.Decision(null, null));
    assertThat(run.decide(lagrange)).isEqualTo(new StarmapLinkRun.Decision(null, null));
  }

  @Test
  void aRowUexReportsNotLive_withoutAHit_opensNoCase_whileALiveOneDoes() {
    StarmapPlaceRow olisar =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.SPACE_STATION, "Port Olisar")
            .live(false)
            .build();
    StarmapPlaceRow everus =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.SPACE_STATION, "Everus Harbor").build();

    assertThat(run().decide(olisar)).isEqualTo(new StarmapLinkRun.Decision(null, null));
    assertThat(run().decide(everus).caseReason()).isEqualTo(P4kReviewReason.STARMAP_LINK_NOT_FOUND);
  }

  @Test
  void aPoiOrOutpostWhoseLinkFeedsNothing_opensNoCase() {
    StarmapPlaceRow poi = StarmapPlaceRowBuilder.row(StarmapPlaceKind.POI, "Aaron Halo").build();
    StarmapPlaceRow outpost =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.OUTPOST, "Bloodshot Ridge").build();

    assertThat(run().decide(poi)).isEqualTo(new StarmapLinkRun.Decision(null, null));
    assertThat(run().decide(outpost)).isEqualTo(new StarmapLinkRun.Decision(null, null));
  }

  @Test
  void anOutpostATerminalNames_opensACase() {
    StarmapPlaceRow outpost =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.OUTPOST, "Bloodshot Ridge").build();
    StarmapPlaceRow terminal =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.TERMINAL, "Admin - Bloodshot Ridge")
            .outpost("Bloodshot Ridge")
            .build();

    assertThat(run(List.of(terminal), Set.of(), List.of()).decide(outpost).caseReason())
        .isEqualTo(P4kReviewReason.STARMAP_LINK_NOT_FOUND);
  }

  @Test
  void aPoiWithAStoragePlaceBelowACandidate_opensACase() {
    UUID first = builder.add("Mining Base #XJZ-JR2", "Asteroid", stanton);
    builder.add("Mining Base #XJZ-JR2", "Asteroid", stanton);
    UUID depot = builder.add("Depot", "Manmade", first);
    StarmapPlaceRow poi =
        StarmapPlaceRowBuilder.row(StarmapPlaceKind.POI, "Mining Base #XJZ-JR2").build();

    assertThat(run().decide(poi)).isEqualTo(new StarmapLinkRun.Decision(null, null));
    assertThat(run(List.of(), Set.of(depot), List.of()).decide(poi).caseReason())
        .isEqualTo(P4kReviewReason.STARMAP_LINK_AMBIGUOUS);
  }
}

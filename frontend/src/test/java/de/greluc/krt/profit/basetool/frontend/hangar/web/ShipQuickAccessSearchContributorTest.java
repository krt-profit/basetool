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

package de.greluc.krt.profit.basetool.frontend.hangar.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.hangar.client.HangarBackendClient;
import de.greluc.krt.profit.basetool.frontend.hangar.model.SquadronShipOverviewDto;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessHit;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchKind;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link ShipQuickAccessSearchContributor}: the unit hangar overview's read. */
@ExtendWith(MockitoExtension.class)
class ShipQuickAccessSearchContributorTest {

  @Mock private HangarBackendClient hangarClient;

  @InjectMocks private ShipQuickAccessSearchContributor contributor;

  /** It searches ships. */
  @Test
  void searchesShips() {
    assertThat(contributor.kind()).isEqualTo(QuickAccessSearchKind.SHIP);
  }

  /** The overview's first page is read with the term and the limit, so the backend scopes it. */
  @Test
  void asksTheUnitOverviewForTheFirstPage() {
    when(hangarClient.squadronOverview(0, 5, "cutl"))
        .thenReturn(new PageResponse<>(List.of(), 0, 5, 0, 0, List.of()));

    contributor.results("cutl", 5);

    verify(hangarClient).squadronOverview(0, 5, "cutl");
  }

  /** Each type becomes a hit on the unit hangar filtered to its name. */
  @Test
  void mapsTypesToFilteredHangarHits() {
    ShipTypeDto type = new ShipTypeDto(UUID.randomUUID(), "Cutlass Black", null, null, 46, false);
    when(hangarClient.squadronOverview(0, 5, "cutl"))
        .thenReturn(
            new PageResponse<>(
                List.of(new SquadronShipOverviewDto(type, 2, 1, List.of())),
                0,
                5,
                1,
                1,
                List.of()));

    assertThat(contributor.results("cutl", 5))
        .containsExactly(
            new QuickAccessHit(
                "/hangar/squadron?search=Cutlass%20Black", "Cutlass Black", null, null));
  }

  /** A response without a body yields no hit. */
  @Test
  void noBodyYieldsNoHit() {
    when(hangarClient.squadronOverview(0, 5, "zz")).thenReturn(null);

    assertThat(contributor.results("zz", 5)).isEmpty();
  }
}

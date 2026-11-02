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

package de.greluc.krt.profit.basetool.frontend.blueprint.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.blueprint.client.BlueprintBackendClient;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintOverviewEntryDto;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessHit;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchKind;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link BlueprintQuickAccessSearchContributor}: the availability overview's scoped
 * read.
 */
@ExtendWith(MockitoExtension.class)
class BlueprintQuickAccessSearchContributorTest {

  @Mock private BlueprintBackendClient blueprintClient;

  @InjectMocks private BlueprintQuickAccessSearchContributor contributor;

  /** It searches blueprints. */
  @Test
  void searchesBlueprints() {
    assertThat(contributor.kind()).isEqualTo(QuickAccessSearchKind.BLUEPRINT);
  }

  /** The overview's first page is read with the term and the limit, so the backend scopes it. */
  @Test
  void asksTheOverviewReadForTheFirstPage() {
    when(blueprintClient.overviewPage(0, 5, "rifle"))
        .thenReturn(new PageResponse<>(List.of(), 0, 5, 0, 0, List.of()));

    contributor.results("rifle", 5);

    verify(blueprintClient).overviewPage(0, 5, "rifle");
  }

  /** Each product becomes a hit on the overview filtered to its name. */
  @Test
  void mapsProductsToFilteredOverviewHits() {
    when(blueprintClient.overviewPage(0, 5, "rif"))
        .thenReturn(
            new PageResponse<>(
                List.of(new BlueprintOverviewEntryDto("k", "Rifle & Sons", 3)),
                0,
                5,
                1,
                1,
                List.of()));

    assertThat(contributor.results("rif", 5))
        .containsExactly(
            new QuickAccessHit(
                "/blueprint-overview?search=Rifle%20%26%20Sons", "Rifle & Sons", null, null));
  }

  /** A response without a body yields no hit. */
  @Test
  void noBodyYieldsNoHit() {
    when(blueprintClient.overviewPage(0, 5, "x1")).thenReturn(null);

    assertThat(contributor.results("x1", 5)).isEmpty();
  }
}

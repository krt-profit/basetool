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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
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

/** Unit tests for {@link MaterialQuickAccessSearchContributor}: the visible catalogue's search. */
@ExtendWith(MockitoExtension.class)
class MaterialQuickAccessSearchContributorTest {

  @Mock private CatalogueBackendClient catalogueClient;

  @InjectMocks private MaterialQuickAccessSearchContributor contributor;

  /** It searches materials. */
  @Test
  void searchesMaterials() {
    assertThat(contributor.kind()).isEqualTo(QuickAccessSearchKind.MATERIAL);
  }

  /** Each visible material the search finds becomes a hit on its detail page. */
  @Test
  void mapsVisibleMaterialsToDetailHits() {
    UUID id = UUID.fromString("00000000-0000-0000-0000-000000000003");
    when(catalogueClient.searchMaterials("quan", false, false, 5))
        .thenReturn(new PageResponse<>(List.of(material(id)), 0, 5, 1, 1, List.of()));

    assertThat(contributor.results("quan", 5))
        .containsExactly(
            new QuickAccessHit(
                "/materials/00000000-0000-0000-0000-000000000003", "Quantanium", null, null));
  }

  /** A response without a body yields no hit. */
  @Test
  void noBodyYieldsNoHit() {
    when(catalogueClient.searchMaterials("quan", false, false, 5)).thenReturn(null);

    assertThat(contributor.results("quan", 5)).isEmpty();
  }

  private static MaterialDto material(UUID id) {
    return new MaterialDto(
        id,
        "Quantanium",
        null,
        null,
        null,
        null,
        null,
        false,
        false,
        false,
        false,
        false,
        false,
        true,
        1L);
  }
}

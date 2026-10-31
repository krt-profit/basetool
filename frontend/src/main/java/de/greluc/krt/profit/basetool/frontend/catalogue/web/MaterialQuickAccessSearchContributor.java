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

import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessHit;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchContributor;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchKind;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Finds materials of the visible catalogue by name, the materials the material pages show every
 * signed-in user (REQ-UI-033).
 */
@Component
@Order(3)
@RequiredArgsConstructor
public class MaterialQuickAccessSearchContributor implements QuickAccessSearchContributor {

  private final CatalogueBackendClient catalogueClient;

  /**
   * Searches materials.
   *
   * @return {@link QuickAccessSearchKind#MATERIAL}
   */
  @Override
  public @NotNull QuickAccessSearchKind kind() {
    return QuickAccessSearchKind.MATERIAL;
  }

  /**
   * Reads the first page of matching visible materials, by name.
   *
   * @param query the search term
   * @param limit the page size
   * @return the materials as hits on their detail pages, without status or date
   */
  @Override
  public @NotNull List<QuickAccessHit> results(@NotNull String query, int limit) {
    PageResponse<MaterialDto> page = catalogueClient.searchMaterials(query, false, false, limit);
    if (page == null || page.content() == null) {
      return List.of();
    }
    return page.content().stream()
        .map(m -> new QuickAccessHit("/materials/" + m.id(), m.name(), null, null))
        .toList();
  }
}

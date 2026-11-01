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

import de.greluc.krt.profit.basetool.frontend.blueprint.client.BlueprintBackendClient;
import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintOverviewEntryDto;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessHit;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessHrefs;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchContributor;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchKind;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Finds blueprints by product name through the availability overview's read, so a hit is a product
 * the overview would list for the caller (REQ-UI-033).
 */
@Component
@Order(4)
@RequiredArgsConstructor
public class BlueprintQuickAccessSearchContributor implements QuickAccessSearchContributor {

  private static final String OVERVIEW_PATH = "/blueprint-overview";

  private final BlueprintBackendClient blueprintClient;

  /**
   * Searches blueprints.
   *
   * @return {@link QuickAccessSearchKind#BLUEPRINT}
   */
  @Override
  public @NotNull QuickAccessSearchKind kind() {
    return QuickAccessSearchKind.BLUEPRINT;
  }

  /**
   * Reads the first page of matching products of the overview.
   *
   * @param query the search term
   * @param limit the page size
   * @return the products as hits on the overview filtered to their name, without status or date
   */
  @Override
  public @NotNull List<QuickAccessHit> results(@NotNull String query, int limit) {
    PageResponse<BlueprintOverviewEntryDto> page = blueprintClient.overviewPage(0, limit, query);
    if (page == null || page.content() == null) {
      return List.of();
    }
    return page.content().stream()
        .map(e ->
            new QuickAccessHit(
                QuickAccessHrefs.filteredBy(OVERVIEW_PATH, e.productName()),
                e.productName(),
                null,
                null))
        .toList();
  }
}

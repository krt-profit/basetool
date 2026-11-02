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

import de.greluc.krt.profit.basetool.frontend.hangar.client.HangarBackendClient;
import de.greluc.krt.profit.basetool.frontend.hangar.model.SquadronShipOverviewDto;
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
 * Finds ship types through the unit hangar's overview read, so a hit is a type the overview would
 * list for the caller's units (REQ-UI-033).
 */
@Component
@Order(5)
@RequiredArgsConstructor
public class ShipQuickAccessSearchContributor implements QuickAccessSearchContributor {

  private static final String UNIT_HANGAR_PATH = "/hangar/squadron";

  private final HangarBackendClient hangarClient;

  /**
   * Searches ships.
   *
   * @return {@link QuickAccessSearchKind#SHIP}
   */
  @Override
  public @NotNull QuickAccessSearchKind kind() {
    return QuickAccessSearchKind.SHIP;
  }

  /**
   * Reads the first page of matching ship types of the unit overview.
   *
   * @param query the search term
   * @param limit the page size
   * @return the types as hits on the unit hangar filtered to the type, without status or date
   */
  @Override
  public @NotNull List<QuickAccessHit> results(@NotNull String query, int limit) {
    PageResponse<SquadronShipOverviewDto> page = hangarClient.squadronOverview(0, limit, query);
    if (page == null || page.content() == null) {
      return List.of();
    }
    return page.content().stream()
        .filter(o -> o.shipType() != null)
        .map(o ->
            new QuickAccessHit(
                QuickAccessHrefs.filteredBy(UNIT_HANGAR_PATH, o.shipType().name()),
                o.shipType().name(),
                null,
                null))
        .toList();
  }
}

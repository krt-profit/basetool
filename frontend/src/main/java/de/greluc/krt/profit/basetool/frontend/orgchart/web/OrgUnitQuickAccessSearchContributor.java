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

import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessHit;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchContributor;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessSearchKind;
import de.greluc.krt.profit.basetool.frontend.orgchart.client.OrgChartBackendClient;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Finds the org units the org chart shows, by name or tag, so a hit is a unit of the chart the
 * caller can open (REQ-UI-033).
 */
@Component
@Order(6)
@RequiredArgsConstructor
public class OrgUnitQuickAccessSearchContributor implements QuickAccessSearchContributor {

  private final OrgChartBackendClient orgChartClient;

  /**
   * Searches org units.
   *
   * @return {@link QuickAccessSearchKind#ORG_UNIT}
   */
  @Override
  public @NotNull QuickAccessSearchKind kind() {
    return QuickAccessSearchKind.ORG_UNIT;
  }

  /**
   * Filters the org chart read for matching units.
   *
   * @param query the search term
   * @param limit the most hits
   * @return the units as hits on the org chart with the unit highlighted, without status or date
   */
  @Override
  public @NotNull List<QuickAccessHit> results(@NotNull String query, int limit) {
    return OrgChartSearchEntries.matchingUnits(orgChartClient.orgChart(), query, limit).stream()
        .map(u ->
            new QuickAccessHit(OrgChartPageController.highlightHref(u.id()), u.name(), null, null))
        .toList();
  }
}

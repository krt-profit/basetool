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

package de.greluc.krt.profit.basetool.frontend.supply.web;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessContributor;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessEntry;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import de.greluc.krt.profit.basetool.frontend.supply.client.SupplyBackendClient;
import de.greluc.krt.profit.basetool.frontend.supply.model.SupplyUnitDto;
import de.greluc.krt.profit.basetool.frontend.supply.web.SupplyPageController.SupplyTabs;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Offers the quick access the supply tabs the caller sees: „Eingehende Spenden" and „Statistik"
 * (REQ-UI-026, REQ-SUP-001). Nothing while the supply surface is off.
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class SupplyQuickAccessContributor implements QuickAccessContributor {

  private static final String CRUMB = "nav.section.supply";
  private static final String ICON = "download";
  private static final String PAGE = "supply.needs";

  private final SupplyBackendClient supplyClient;
  private final SupplyFeatureState featureState;
  private final FrontendAuthHelperService authHelper;

  /**
   * Lists the incoming donations and the statistics when the supply tabs show them to the caller.
   *
   * @return the entries; none while the surface is off or the units cannot be read
   */
  @Override
  public @NotNull List<QuickAccessEntry> entries() {
    if (!featureState.enabled()) {
      return List.of();
    }
    SupplyTabs tabs =
        SectionFallback.load(
            log,
            "quick-access-supply",
            () -> {
              List<SupplyUnitDto> units = supplyClient.units();
              return SupplyTabs.of(units == null ? List.of() : units, authHelper.isAdmin());
            },
            new SupplyTabs(false, false));
    List<QuickAccessEntry> out = new ArrayList<>(2);
    if (tabs.incoming()) {
      out.add(
          new QuickAccessEntry(
              "/supply/donations/incoming",
              "palette.page.supplyIncoming",
              CRUMB,
              ICON,
              "palette.keywords.supplyIncoming",
              PAGE));
    }
    if (tabs.stats()) {
      out.add(
          new QuickAccessEntry(
              "/supply/stats",
              "palette.page.supplyStats",
              CRUMB,
              ICON,
              "palette.keywords.supplyStats",
              PAGE));
    }
    return List.copyOf(out);
  }
}

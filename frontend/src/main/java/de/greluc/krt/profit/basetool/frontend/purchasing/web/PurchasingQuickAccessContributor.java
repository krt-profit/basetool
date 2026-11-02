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

package de.greluc.krt.profit.basetool.frontend.purchasing.web;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessContributor;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessEntry;
import de.greluc.krt.profit.basetool.frontend.purchasing.web.PurchasingPageController.PurchasingTabs;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Offers the quick access the purchasing tabs the caller sees: „Partner", „Handelsbeauftragte" and
 * „Bericht" (REQ-UI-026, REQ-PUR-001), by the tab flags of the caller's layout read. Nothing while
 * the purchasing surface is off.
 */
@Component
@Order(2)
@RequiredArgsConstructor
public class PurchasingQuickAccessContributor implements QuickAccessContributor {

  private static final String CRUMB = "nav.section.supply";
  private static final String ICON = "download";
  private static final String PAGE = "purchasing.deals";

  private final PurchasingFeatureState featureState;
  private final LayoutContextLoader layoutContextLoader;
  private final HttpServletRequest request;

  /**
   * Lists the partners, the delegates and the report when the purchasing tabs show them to the
   * caller.
   *
   * @return the entries; none while the surface is off or the layout read failed
   */
  @Override
  public @NotNull List<QuickAccessEntry> entries() {
    if (!featureState.enabled()) {
      return List.of();
    }
    PurchasingTabs tabs = PurchasingTabs.of(layoutContextLoader.capabilities(request));
    List<QuickAccessEntry> out = new ArrayList<>(3);
    if (tabs.partners()) {
      out.add(
          entry(
              "/purchasing/partners",
              "palette.page.purchasingPartners",
              "palette.keywords.purchasingPartners",
              "purchasing.partners"));
    }
    if (tabs.delegates()) {
      out.add(
          entry(
              "/purchasing/delegates",
              "palette.page.purchasingDelegates",
              "palette.keywords.purchasingDelegates",
              "purchasing.delegates"));
    }
    if (tabs.report()) {
      out.add(
          entry(
              "/purchasing/report",
              "palette.page.purchasingReport",
              "palette.keywords.purchasingReport",
              "purchasing.report"));
    }
    return List.copyOf(out);
  }

  private static @NotNull QuickAccessEntry entry(
      @NotNull String href,
      @NotNull String labelKey,
      @NotNull String keywordsKey,
      @NotNull String entryKey) {
    return QuickAccessEntry.tab(href, labelKey, CRUMB, ICON, keywordsKey, PAGE, entryKey);
  }
}

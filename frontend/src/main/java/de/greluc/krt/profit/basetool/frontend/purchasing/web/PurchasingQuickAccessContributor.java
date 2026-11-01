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

import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessContributor;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.QuickAccessEntry;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import de.greluc.krt.profit.basetool.frontend.purchasing.client.PurchasingBackendClient;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingStateDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.web.PurchasingPageController.PurchasingTabs;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Offers the quick access the purchasing tabs the caller sees: „Partner", „Handelsbeauftragte" and
 * „Bericht" (REQ-UI-026, REQ-PUR-001). Nothing while the purchasing surface is off.
 */
@Component
@Order(2)
@RequiredArgsConstructor
@Slf4j
public class PurchasingQuickAccessContributor implements QuickAccessContributor {

  private static final String CRUMB = "nav.section.supply";
  private static final String ICON = "download";
  private static final String PAGE = "purchasing.deals";

  private final PurchasingBackendClient purchasingClient;
  private final PurchasingFeatureState featureState;

  /**
   * Lists the partners, the delegates and the report when the purchasing tabs show them to the
   * caller.
   *
   * @return the entries; none while the surface is off or the state cannot be read
   */
  @Override
  public @NotNull List<QuickAccessEntry> entries() {
    if (!featureState.enabled()) {
      return List.of();
    }
    PurchasingTabs tabs =
        SectionFallback.load(
            log,
            "quick-access-purchasing",
            () ->
                Optional.ofNullable(purchasingClient.state())
                    .filter(PurchasingStateDto::enabled)
                    .map(PurchasingTabs::of)
                    .orElse(new PurchasingTabs(false, false, false)),
            new PurchasingTabs(false, false, false));
    List<QuickAccessEntry> out = new ArrayList<>(3);
    if (tabs.partners()) {
      out.add(
          entry(
              "/purchasing/partners",
              "palette.page.purchasingPartners",
              "palette.keywords.purchasingPartners"));
    }
    if (tabs.delegates()) {
      out.add(
          entry(
              "/purchasing/delegates",
              "palette.page.purchasingDelegates",
              "palette.keywords.purchasingDelegates"));
    }
    if (tabs.report()) {
      out.add(
          entry(
              "/purchasing/report",
              "palette.page.purchasingReport",
              "palette.keywords.purchasingReport"));
    }
    return List.copyOf(out);
  }

  private static @NotNull QuickAccessEntry entry(
      @NotNull String href, @NotNull String labelKey, @NotNull String keywordsKey) {
    return new QuickAccessEntry(href, labelKey, CRUMB, ICON, keywordsKey, PAGE);
  }
}

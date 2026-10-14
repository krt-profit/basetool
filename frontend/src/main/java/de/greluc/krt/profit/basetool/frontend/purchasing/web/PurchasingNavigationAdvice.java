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
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Exposes {@code purchasingEnabled} to every page that renders the layout, so the sidebar shows the
 * „Ankauf" entry only while the purchasing surface is on (REQ-PUR-001). Fails closed.
 */
@ControllerAdvice(annotations = UsesLayoutModel.class)
@RequiredArgsConstructor
public class PurchasingNavigationAdvice {

  private final PurchasingFeatureState featureState;
  private final FrontendAuthHelperService authHelper;

  /**
   * Whether the sidebar shows the purchasing entry.
   *
   * @param request the current request, whose handler decides whether a layout is rendered
   * @return {@code true} for an authenticated caller on a rendered page while the purchasing
   *     surface is on
   */
  @ModelAttribute("purchasingEnabled")
  public boolean purchasingEnabled(HttpServletRequest request) {
    return LayoutContextLoader.needsLayoutModel(request)
        && authHelper.isAuthenticated()
        && featureState.enabled();
  }
}

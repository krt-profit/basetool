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

package de.greluc.krt.profit.basetool.frontend.controller;

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.oss.OssLicenseCatalog;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The „Open-Source-Lizenzen“ page ({@code /licenses}, REQ-UI-021): every third-party component the
 * Basetool ships, grouped by licence, linked from the footer beside the Nutzungsbedingungen.
 *
 * <p>Public by design, like the other legal pages (REQ-SEC-052): the notice is owed to whoever
 * receives the software, and the landing page already serves the bundled font to a visitor who
 * holds no session. It makes no backend call — the list is read once at startup from the report the
 * build generated — so there is nothing behind it a login would protect.
 */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
public class OssLicensesController {

  /** The build-time licence report, grouped and ready to render. */
  private final OssLicenseCatalog catalog;

  /**
   * Renders the licence overview.
   *
   * <p>Adds {@code ossAvailable}, {@code ossGroups}, {@code ossComponentCount} and {@code
   * ossGenerator} to the model; when the report could not be read, {@code ossAvailable} is {@code
   * false} and the template shows a notice instead of an empty list that would read as "nothing
   * third-party ships".
   *
   * @param model the view model the four attributes are added to
   * @return the {@code licenses} view name
   */
  @NotNull
  @GetMapping("/licenses")
  public String showLicenses(@NotNull Model model) {
    model.addAttribute("ossAvailable", catalog.isAvailable());
    model.addAttribute("ossGroups", catalog.groups());
    model.addAttribute("ossComponentCount", catalog.componentCount());
    model.addAttribute("ossGenerator", catalog.generator());
    return "licenses";
  }
}

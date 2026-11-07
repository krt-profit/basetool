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

import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Admin-only page {@code /admin/starmap} (REQ-P4K-034…038): the P4K starmap objects with their
 * visibility and storage places, and the links of the UEX location rows. The page renders empty
 * shells; its script fills both lists through {@link AdminStarmapController}.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/starmap")
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminStarmapPageController {

  /** The UEX location tables the link section offers, in display order. */
  static final @Unmodifiable List<String> PLACE_KINDS =
      List.of(
          "STAR_SYSTEM",
          "ORBIT",
          "PLANET",
          "MOON",
          "CITY",
          "OUTPOST",
          "SPACE_STATION",
          "POI",
          "TERMINAL");

  /**
   * Renders the starmap page with the UEX tables its link section offers.
   *
   * @param model Thymeleaf model
   * @return the {@code catalogue/admin/starmap} view name
   */
  @NotNull
  @GetMapping
  public String view(@NotNull Model model) {
    model.addAttribute("placeKinds", PLACE_KINDS);
    return "catalogue/admin/starmap";
  }
}

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
import de.greluc.krt.profit.basetool.frontend.model.ScLinkCategory;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The „Star-Citizen-Links" page ({@code /sc-links}, REQ-UI-025): a static, categorised list of
 * external Star Citizen websites for every signed-in member. It makes no backend call.
 */
@Controller
@UsesLayoutModel
@PreAuthorize("isAuthenticated()")
public class ScLinksPageController {

  /**
   * Renders the link list.
   *
   * @param model the view model that receives {@code scLinkCategories}, every section in page order
   * @return the {@code sc-links} view name
   */
  @NotNull
  @GetMapping("/sc-links")
  public String showLinks(@NotNull Model model) {
    model.addAttribute("scLinkCategories", List.of(ScLinkCategory.values()));
    return "sc-links";
  }
}

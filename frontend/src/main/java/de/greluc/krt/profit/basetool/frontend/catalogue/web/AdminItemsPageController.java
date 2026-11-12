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
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Admin-only page {@code /admin/items} (REQ-P4K-048): the catalogue's game items, each hidden as not
 * obtainable in the game or offered in the item pickers. The page renders an empty shell; its
 * script fills the list through {@link AdminItemsController}.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/items")
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminItemsPageController {

  /**
   * Renders the item page.
   *
   * @return the {@code catalogue/admin/items} view name
   */
  @NotNull
  @GetMapping
  public String view() {
    return "catalogue/admin/items";
  }
}

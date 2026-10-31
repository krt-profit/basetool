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

package de.greluc.krt.profit.basetool.frontend.navigation.web;

import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The admin hub ({@code /admin}): the landing page of admin mode with one tile per administration
 * link (REQ-UI-026).
 *
 * <p>It lists the {@code navigation} model attribute and reads nothing from the backend.
 */
@Controller
@UsesLayoutModel
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminHubPageController {

  /**
   * Renders the admin hub.
   *
   * @return the {@code navigation/admin-hub} view name
   */
  @NotNull
  @GetMapping("/admin")
  public String hub() {
    return "navigation/admin-hub";
  }
}

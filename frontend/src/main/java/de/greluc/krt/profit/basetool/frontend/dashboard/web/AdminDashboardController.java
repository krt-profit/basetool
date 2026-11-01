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

package de.greluc.krt.profit.basetool.frontend.dashboard.web;

import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardMode;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.security.Roles;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The admin dashboard ({@code /admin}): the landing page of admin mode, a widget board of its own
 * with the hub of admin links as one widget (REQ-UI-034).
 */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminDashboardController {

  private final DashboardReader reader;

  /**
   * Renders the admin dashboard; its widgets load themselves.
   *
   * @param model the Thymeleaf model, filled with {@code dashboard}
   * @return the {@code dashboard/index} view name
   */
  @NotNull
  @GetMapping("/admin")
  public String dashboard(@NotNull Model model) {
    DashboardContext context = DashboardContext.of(model);
    model.addAttribute(
        "dashboard",
        reader.read(
            DashboardMode.ADMIN, context.access(), context.activeUnitId(), context.units()));
    return "dashboard/index";
  }
}

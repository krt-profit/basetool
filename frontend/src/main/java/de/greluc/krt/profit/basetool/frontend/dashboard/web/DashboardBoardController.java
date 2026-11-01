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
import de.greluc.krt.profit.basetool.frontend.dashboard.model.DashboardView;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the member dashboard's board as a fragment, so the page swaps it in place. */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class DashboardBoardController {

  private final DashboardReader reader;

  /**
   * Renders the member dashboard's board: both columns with their instances.
   *
   * @param model the Thymeleaf model, filled with {@code dashboard}
   * @return the {@code dashboard/fragments/board} fragment
   */
  @NotNull
  @GetMapping(value = "/dashboard/member/board", headers = "X-Requested-With=XMLHttpRequest")
  public String board(@NotNull Model model) {
    DashboardContext context = DashboardContext.of(model);
    DashboardView dashboard =
        reader.read(
            DashboardMode.MEMBER, context.access(), context.activeUnitId(), context.units());
    model.addAttribute("dashboard", dashboard);
    return "dashboard/fragments/board :: board";
  }
}

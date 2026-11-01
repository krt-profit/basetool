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
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

/** Serves a mode's dashboard board as a fragment, so the page swaps it in place. */
@Controller
@UsesLayoutModel
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated() and (#mode != 'admin' or hasRole('ADMIN'))")
public class DashboardBoardController {

  private final DashboardReader reader;

  /**
   * Renders a mode's dashboard board: both columns with their instances.
   *
   * @param mode the mode's path segment; {@code admin} is for administrators only
   * @param model the Thymeleaf model, filled with {@code dashboard}
   * @return the {@code dashboard/fragments/board} fragment
   * @throws ResponseStatusException with {@code 404} for an unknown mode
   */
  @NotNull
  @GetMapping(value = "/dashboard/{mode}/board", headers = "X-Requested-With=XMLHttpRequest")
  public String board(@PathVariable String mode, @NotNull Model model) {
    Optional<DashboardMode> resolved = DashboardMode.fromSlug(mode);
    if (resolved.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    DashboardContext context = DashboardContext.of(model);
    DashboardView dashboard =
        reader.read(resolved.get(), context.access(), context.activeUnitId(), context.units());
    model.addAttribute("dashboard", dashboard);
    return "dashboard/fragments/board :: board";
  }
}

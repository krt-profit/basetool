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
import org.jetbrains.annotations.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The favourites as a fragment for the in-place swap (REQ-UI-032): after the star wrote the
 * section, the script re-reads both modes' lists and the state from here.
 */
@Controller
@UsesLayoutModel
@PreAuthorize("isAuthenticated()")
public class NavigationFavouritesFragmentController {

  /**
   * Renders the favourites of both modes and their state.
   *
   * @return the {@code kernel/fragments/nav-favourites} fragment
   */
  @NotNull
  @GetMapping(value = "/navigation/favourites", headers = "X-Requested-With=XMLHttpRequest")
  public String fragment() {
    return "kernel/fragments/nav-favourites :: both";
  }
}

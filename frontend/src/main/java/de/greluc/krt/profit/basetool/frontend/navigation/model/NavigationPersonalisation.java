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

package de.greluc.krt.profit.basetool.frontend.navigation.model;

import org.jetbrains.annotations.NotNull;

/**
 * The stored navigation preferences of the caller as the navigation renders them (REQ-UI-032).
 *
 * @param favourites the favourites section
 * @param hidden the hidden-pages section
 */
public record NavigationPersonalisation(
    @NotNull NavigationFavourites favourites, @NotNull NavigationHidden hidden) {

  /** The preferences of a caller whose preferences were not read: nothing stored. */
  public static final NavigationPersonalisation NOT_LOADED =
      new NavigationPersonalisation(NavigationFavourites.NOT_LOADED, NavigationHidden.NOT_LOADED);
}

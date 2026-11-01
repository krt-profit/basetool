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

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import org.jetbrains.annotations.NotNull;

/**
 * Every navigation preference section of the caller, each with its own version.
 *
 * @param favourites the favourites section
 * @param hidden the hidden-pages section
 * @param order the order section, one order and version per mode
 * @param appearance the appearance section
 */
@DtoMirror
public record NavigationPreferencesResponse(
    @NotNull FavouritesResponse favourites,
    @NotNull HiddenResponse hidden,
    @NotNull OrderResponse order,
    @NotNull AppearanceResponse appearance) {

  /**
   * Fills a section the backend did not send with its empty default.
   *
   * @param favourites the favourites section
   * @param hidden the hidden-pages section
   * @param order the order section
   * @param appearance the appearance section
   */
  public NavigationPreferencesResponse {
    favourites = favourites == null ? new FavouritesResponse(null, 0) : favourites;
    hidden = hidden == null ? HiddenResponse.EMPTY : hidden;
    order = order == null ? OrderResponse.EMPTY : order;
    appearance = appearance == null ? AppearanceResponse.DRAWER : appearance;
  }
}

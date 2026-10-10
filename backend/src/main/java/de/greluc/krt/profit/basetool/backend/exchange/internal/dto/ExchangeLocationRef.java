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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.jetbrains.annotations.Nullable;

/**
 * A Lager location as a client names it in a write, {@code location-ref.schema.json}: first by its
 * UEX place, then by its exact name.
 *
 * @param name the location's name, or {@code null}
 * @param uex the UEX place, or {@code null}
 */
public record ExchangeLocationRef(
    @Nullable @Size(min = 1, max = 200) String name, @Nullable @Valid Uex uex) {

  /**
   * A UEX place.
   *
   * @param kind {@code CITY} or {@code SPACE_STATION}
   * @param id the UEX id
   */
  public record Uex(
      @NotNull @Pattern(regexp = "^(CITY|SPACE_STATION)$") String kind,
      @NotNull @Min(1) Integer id) {}
}

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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import org.jetbrains.annotations.NotNull;

/**
 * The base value of one stat of a blueprint's crafted item, before ingredient quality applies; the
 * value at a quality is {@code baseValue} times the modifier's factor (REQ-INV-069).
 *
 * @param propertyKey the SC Wiki property key the modifiers name, e.g. {@code "health_maxhealth"}
 * @param baseValue the base value in {@code unit}; a {@code PERCENT} value is a fraction of one
 * @param unit the unit of {@code baseValue}
 */
public record BlueprintStatBaseDto(
    @NotNull String propertyKey, double baseValue, @NotNull BlueprintStatUnit unit) {

  /**
   * Maps a stored base value to its DTO.
   *
   * @param stat the stored stat row
   * @return the DTO carrying the row's property key, value and unit
   */
  @NotNull
  public static BlueprintStatBaseDto of(@NotNull GameItemStat stat) {
    BlueprintStatProperty property = stat.getProperty();
    return new BlueprintStatBaseDto(
        property.getPropertyKey(), stat.getBaseValue(), property.getUnit());
  }
}

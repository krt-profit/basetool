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
import org.jetbrains.annotations.Nullable;

/** Normalized Wiki vehicle capacities and durability, with the source build. */
public record ShipCatalogueFacts(
    @Nullable Double inventoryScu,
    @Nullable Double oreCapacityScu,
    @Nullable Integer medicalTier,
    @Nullable Double hullHealth,
    @Nullable Double shieldHealth,
    @Nullable String gameVersion) {

  /**
   * Projects only scalar catalogue fields without loading a relationship.
   *
   * @param ship catalogue vehicle
   * @return normalized capacities and durability
   */
  public static ShipCatalogueFacts of(@NotNull ShipType ship) {
    return new ShipCatalogueFacts(
        ship.getVehicleInventoryScu(),
        ship.getOreCapacity(),
        ship.getMaxMedicalTier(),
        ship.getHealth(),
        ship.getShieldHp(),
        ship.getScwikiGameVersionSeen());
  }
}

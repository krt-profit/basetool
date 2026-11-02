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

package de.greluc.krt.profit.basetool.backend.supply.api;

import org.jetbrains.annotations.NotNull;

/**
 * Tells the caller's layout read which supply tabs the caller may open (REQ-UI-026), by the same
 * rules the supply reads behind those tabs enforce.
 */
public interface SupplyTabAccess {

  /**
   * Decides the supply tabs for the calling member; both are off while the surface is switched off.
   *
   * @return the tabs the caller may open
   */
  @NotNull
  SupplyTabGrants current();

  /**
   * The supply tabs the caller may open.
   *
   * @param incoming whether „Eingehende Spenden" is offered: the caller manages, or as an admin
   *     reaches, an active unit that accepts donations
   * @param stats whether „Statistik" is offered: the caller belongs to the governing leadership or
   *     manages at least one unit
   */
  record SupplyTabGrants(boolean incoming, boolean stats) {

    /** Neither tab: the answer while the supply surface is off. */
    public static final SupplyTabGrants NONE = new SupplyTabGrants(false, false);
  }
}

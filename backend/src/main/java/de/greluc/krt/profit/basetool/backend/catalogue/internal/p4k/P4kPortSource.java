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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k;

import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/** The {@code source} of a loadout port: where its attachment comes from (contract, "Ports"). */
@RequiredArgsConstructor
public enum P4kPortSource implements P4kClosedSet {

  /** The entity's own Default Loadout. */
  LOADOUT("loadout"),

  /** The Loadout of the entry that attached the entity, replacing the entity's own per port. */
  OVERRIDE("override"),

  /** The default item of the port definition. */
  PORT_DEFAULT("port_default"),

  /** Defined and holds nothing. */
  UNSET("unset"),

  /** Known only from the vehicle implementation and holds nothing. */
  VEHICLE("vehicle");

  /** The value as the catalogue writes it. */
  @NotNull private final String wireValue;

  /**
   * Returns the value as the catalogue writes it.
   *
   * @return the wire value
   */
  @NotNull
  @Override
  public String wireValue() {
    return wireValue;
  }
}

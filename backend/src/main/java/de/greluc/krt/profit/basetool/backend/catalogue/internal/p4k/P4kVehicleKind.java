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

import de.greluc.krt.profit.basetool.backend.catalogue.api.ShipVehicleKind;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;

/** The {@code vehicleKind} of a catalogue ship (contract, "Ship base data"). */
@RequiredArgsConstructor
public enum P4kVehicleKind implements P4kClosedSet {

  /** A spaceship. */
  SPACESHIP("spaceship"),

  /** A ground vehicle. */
  GROUND_VEHICLE("groundVehicle");

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

  /**
   * Returns the stored kind.
   *
   * @return the {@code ship_type.vehicle_kind} value
   */
  @NotNull
  public ShipVehicleKind toShipVehicleKind() {
    return switch (this) {
      case SPACESHIP -> ShipVehicleKind.SPACESHIP;
      case GROUND_VEHICLE -> ShipVehicleKind.GROUND_VEHICLE;
    };
  }
}

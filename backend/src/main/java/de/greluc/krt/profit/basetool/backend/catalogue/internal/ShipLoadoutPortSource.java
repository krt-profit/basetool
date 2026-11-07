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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

/**
 * Where the attachment of a stored loadout port comes from (REQ-P4K-040); stored in {@code
 * ship_type_loadout_port.source}.
 */
public enum ShipLoadoutPortSource {

  /** The entity's own Default Loadout. */
  LOADOUT,

  /** The Loadout of the entry that attached the entity. */
  OVERRIDE,

  /** The default item of the port definition. */
  PORT_DEFAULT,

  /** Defined and holds nothing. */
  UNSET,

  /** Known only from the vehicle implementation and holds nothing. */
  VEHICLE
}

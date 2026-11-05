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

/** The {@code playability} of a catalogue ship (contract, "Playability"). */
@RequiredArgsConstructor
public enum P4kShipPlayability implements P4kClosedSet {

  /** A player can own or buy the ship. */
  PLAYER("player"),

  /** An AI variant no player can own. */
  AI("ai"),

  /** A ship that exists for missions only. */
  MISSION("mission"),

  /** A special ship no rule classifies as player content. */
  SPECIAL("special");

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

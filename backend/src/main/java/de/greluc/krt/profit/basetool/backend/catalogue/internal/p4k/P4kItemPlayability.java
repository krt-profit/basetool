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

/** The {@code playability} of a catalogue item (contract, "Playability"). */
@RequiredArgsConstructor
public enum P4kItemPlayability implements P4kClosedSet {

  /** A player can own, buy or fit the item. */
  PLAYER("player"),

  /** The item exists for missions only. */
  MISSION("mission"),

  /** A special item no rule classifies as player content. */
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

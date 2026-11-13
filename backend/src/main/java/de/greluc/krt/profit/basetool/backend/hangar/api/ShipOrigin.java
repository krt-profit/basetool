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

package de.greluc.krt.profit.basetool.backend.hangar.api;

/**
 * Whether a ship was pledged, bought in-game, or is of unknown origin (REQ-HANGAR-017). A wipe can
 * remove only {@link #IN_GAME} ships; {@link #UNKNOWN} counts like {@link #PLEDGE} there.
 */
public enum ShipOrigin {
  /** Bought with real money on the RSI website; survives every wipe. */
  PLEDGE,
  /** Bought with in-game currency; a wipe can remove it. */
  IN_GAME,
  /** Not chosen yet; treated like a pledge. */
  UNKNOWN;

  /**
   * Whether an owner may choose this origin for a new ship in the web form, which offers only the
   * two known origins.
   *
   * @return {@code true} for {@link #PLEDGE} and {@link #IN_GAME}
   */
  public boolean isChoice() {
    return this != UNKNOWN;
  }

  /**
   * Returns the origin a ship reads as everywhere: {@link #PLEDGE} while an import or a pledge id
   * locks it, the chosen origin otherwise.
   *
   * @param chosen the origin the owner or an admin chose
   * @param locked whether the origin is locked
   * @return the effective origin
   */
  public static ShipOrigin effective(ShipOrigin chosen, boolean locked) {
    return locked ? PLEDGE : chosen;
  }
}

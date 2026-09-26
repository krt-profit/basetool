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

package de.greluc.krt.profit.basetool.backend.model;

/**
 * The kind of in-game asset a {@link GameItem} represents. UEX derives it from the category
 * section, the Wiki from the {@code classification} string; on disagreement the more specific kind
 * wins ({@code WEAPON_ATTACHMENT > WEAPON > VEHICLE_WEAPON > VEHICLE_ITEM > GENERIC}).
 */
public enum GameItemKind {

  /** Default; used for rows whose section / classification didn't match a more specific kind. */
  GENERIC,

  /** Vehicle-bound component (cooler, shield, power plant, jump drive, …). */
  VEHICLE_ITEM,

  /** Mounted vehicle weapon. */
  VEHICLE_WEAPON,

  /** Hand-held FPS weapon (rifle, sidearm, …). */
  WEAPON,

  /** Hand-weapon attachment (scope, magazine, barrel mod). */
  WEAPON_ATTACHMENT,

  /** FPS armor piece (helmet, torso, arms, legs, backpack). */
  ARMOR,

  /** Clothing / cosmetic / under-suit. */
  CLOTHING,

  /** Food or drink item. */
  FOOD;

  /**
   * Merges an existing kind with an incoming one: more specific wins, never downgrading to {@link
   * #GENERIC}, regardless of which source wrote last.
   *
   * <p>A {@code null} or {@link #GENERIC} {@code existing} yields to {@code incoming}; {@link
   * #WEAPON_ATTACHMENT} refines {@link #WEAPON} and {@link #VEHICLE_WEAPON} refines {@link
   * #VEHICLE_ITEM}; any other pair keeps {@code existing}.
   *
   * @param existing the kind currently on the row (may be {@code null})
   * @param incoming the kind derived from the current sync pass
   * @return the kind to persist
   */
  public static GameItemKind mergeMoreSpecific(GameItemKind existing, GameItemKind incoming) {
    if (existing == null || existing == GENERIC) {
      return incoming;
    }
    if (incoming == GENERIC || existing == incoming) {
      return existing;
    }
    if (existing == WEAPON && incoming == WEAPON_ATTACHMENT) {
      return incoming;
    }
    if (existing == VEHICLE_ITEM && incoming == VEHICLE_WEAPON) {
      return incoming;
    }
    return existing;
  }
}

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

/** The evidence that a row a second UEX listing created belongs to a game row (REQ-DATA-044). */
enum UexItemTwinRule {

  /** The listing's game UUID is held by one row only, and that row carries the listing's name. */
  GAME_UUID,

  /** The listing carries no game UUID a row holds, and one visible game row carries its name. */
  UNIQUE_NAME,

  /** Two visible game rows carry the listing's name, and one's class name is the other's + _GS. */
  GS_RECORD;

  /**
   * Tells whether the rule rests on a name alone, so the item kind and manufacturer must agree.
   *
   * @return {@code true} for the name rules
   */
  boolean needsCompatibleFacts() {
    return this != GAME_UUID;
  }
}

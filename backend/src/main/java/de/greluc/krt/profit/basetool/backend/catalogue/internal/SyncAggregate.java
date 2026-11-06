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

/** Catalogue slices that have independently recorded external sync attempts. */
public enum SyncAggregate {
  /** One complete source sweep over its independently applied slices. */
  CATALOGUE,
  /** Tradable resources and their market prices. */
  COMMODITY,
  /** Spacecraft and ground vehicles. */
  SHIP_TYPE,
  /** Player equipment and other game items. */
  GAME_ITEM,
  /** Crafting recipes. */
  BLUEPRINT,
  /** In-universe equipment manufacturers. */
  MANUFACTURER,
  /** Star-system reference catalogue. */
  STAR_SYSTEM,
  /** In-universe factions. */
  FACTION,
  /** Legal jurisdictions. */
  JURISDICTION,
  /** Planet reference catalogue. */
  PLANET,
  /** Moon reference catalogue. */
  MOON,
  /** Orbital location catalogue. */
  ORBIT,
  /** City reference catalogue. */
  CITY,
  /** Outpost reference catalogue. */
  OUTPOST,
  /** Points of interest. */
  POI,
  /** Space-station catalogue. */
  SPACE_STATION,
  /** Trade and service terminals. */
  TERMINAL,
  /** Upstream item categories. */
  CATEGORY,
  /** Market prices of game items. */
  ITEM_PRICE,
  /** Refinery processing methods. */
  REFINING_METHOD,
  /** Refinery yield matrix. */
  REFINERY_YIELD
}

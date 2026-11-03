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
 * The kind of UEX location a {@link MaterialOccurrence} points at, one per UEX {@code ids_*} list
 * of {@code GET /commodities} (REQ-DATA-031). The declaration order is the order the occurrences of
 * a material are listed in.
 */
public enum MaterialOccurrenceKind {
  /** A star system, from {@code ids_star_systems}. */
  STAR_SYSTEM,
  /** A planet, from {@code ids_planets}. */
  PLANET,
  /** A moon, from {@code ids_moons}. */
  MOON,
  /** An orbit, from {@code ids_orbits}. */
  ORBIT,
  /** A point of interest, from {@code ids_poi}. */
  POI
}

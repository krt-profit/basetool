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
 * Which external catalogues have written to a {@link GameItem} or {@link ShipType} row. Unlike
 * {@link MaterialSourceSystem} there is no manual value; these rows come only from external
 * catalogues.
 */
public enum GameItemSourceSystem {

  /** Only the UEX sync has written to this row. R2 default. */
  UEX_ONLY,

  /** Only the SC Wiki sync has written to this row. Reached in R3+ for Wiki-only variants. */
  WIKI_ONLY,

  /** Both syncs have written to this row; the canonical fields use the §6.3.3 tie-breaker. */
  BOTH,

  /**
   * The P4K Reader catalog import has touched this row. Normally that is signalled by a non-null
   * {@code p4k_synced_at} instead; this value is accepted by the CHECK constraint but not set by
   * the importer.
   */
  P4K
}

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
 * Tracks which external catalogues a {@link Material} row has been seen in; admin-created rows
 * carry {@link #MANUAL}.
 *
 * <ul>
 *   <li>{@link #UEX_ONLY} → {@link #BOTH} when the Wiki sync matches an existing UEX row.
 *   <li>{@link #WIKI_ONLY} → {@link #BOTH} when a UEX sync picks up a Wiki-imported row.
 *   <li>{@link #MANUAL} → {@link #UEX_ONLY} when a UEX sync adopts the row by name; the Wiki sync
 *       leaves {@link #MANUAL} unchanged.
 * </ul>
 */
public enum MaterialSourceSystem {

  /** The row has only been seen in UEX's commodity catalogue. Default for every pre-R3 row. */
  UEX_ONLY,

  /**
   * The row has only been seen in the SC Wiki catalogue; such rows are inserted with {@code
   * is_visible = false} until an admin reviews them.
   */
  WIKI_ONLY,

  /** Both UEX and SC Wiki carry the row; merged via UUID, alias, or canonical-name match. */
  BOTH,

  /** Admin-created row not linked to either UEX or SC Wiki. */
  MANUAL,

  /**
   * The KRT P4K Reader catalog import has touched this material (commodity enrichment). Like the
   * item / ship lanes, P4K participation is normally signalled by a non-null {@code p4k_synced_at}
   * rather than by flipping {@code source_systems}; this value exists so the (future) CHECK
   * constraint accepts it and an explicit P4K-owned flow may set it if the policy changes.
   */
  P4K
}

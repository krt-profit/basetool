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
 * Tracks which external catalogues a {@link Material} row has been seen in. Written by the UEX and
 * (R3+) SC Wiki sync services; admin-created rows carry {@link #MANUAL} so the UI can badge them.
 *
 * <p>Transitions during sync (see SC_WIKI_SYNC_PLAN.md §6.1):
 *
 * <ul>
 *   <li>{@link #UEX_ONLY} → {@link #BOTH} when the R3 Wiki commodity sync finds a match for an
 *       existing UEX row.
 *   <li>{@link #WIKI_ONLY} → {@link #BOTH} when a subsequent UEX commodity sync picks up a row Wiki
 *       imported first.
 *   <li>{@link #MANUAL} → {@link #UEX_ONLY} when a later UEX commodity sync adopts an admin-created
 *       row by name-match and backfills {@code id_commodity} — {@code UexCommodityService}'s
 *       manual-entry handover, pinned by {@code UexCommodityServiceTest}. {@link #MANUAL} is
 *       therefore <b>not</b> sticky, and the derived {@code MaterialDto.isManualEntry} badge
 *       disappears with it. The SC Wiki commodity sync is the exception: {@code
 *       ScWikiCommoditySyncService} promotes only {@link #UEX_ONLY} → {@link #BOTH}, so a
 *       Wiki-linked manual row keeps {@link #MANUAL} while Wiki columns are written on top of it.
 * </ul>
 *
 * <p>R1 only writes {@link #UEX_ONLY} (every existing row at migration time). The other values
 * become reachable in R3 (Wiki commodity sync) and R8 (post-soak V116 backfill of {@code
 * is_manual_entry → MANUAL}).
 */
public enum MaterialSourceSystem {

  /** The row has only been seen in UEX's commodity catalogue. Default for every pre-R3 row. */
  UEX_ONLY,

  /**
   * The row has only been seen in the SC Wiki commodity catalogue. Wiki-only rows are inserted with
   * {@code is_visible = false} so they don't appear in trading flows until an admin reviews them
   * (SC_WIKI_SYNC_PLAN.md §4.3).
   */
  WIKI_ONLY,

  /** Both UEX and SC Wiki carry the row; merged via UUID, alias, or canonical-name match. */
  BOTH,

  /**
   * Admin-created row that has not (yet) been linked to either UEX or SC Wiki. The post-R8 V116
   * backfill flips every legacy {@code is_manual_entry=true} row into this value.
   */
  MANUAL,

  /**
   * The KRT P4K Reader catalog import has touched this material (commodity enrichment). Like the
   * item / ship lanes, P4K participation is normally signalled by a non-null {@code p4k_synced_at}
   * rather than by flipping {@code source_systems}; this value exists so the (future) CHECK
   * constraint accepts it and an explicit P4K-owned flow may set it if the policy changes.
   */
  P4K
}

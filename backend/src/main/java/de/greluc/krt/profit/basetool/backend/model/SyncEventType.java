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
 * Findings a sync run can record into {@link ExternalSyncReport}; the {@code event_type} column
 * stores the enum name. Optimistic-lock conflicts with admin edits are not recorded: the row is
 * skipped and re-synced on the next run.
 */
public enum SyncEventType {

  /** Wiki commodity sync: a row was dropped by the hard-junk name filter. */
  SKIP_JUNK,

  /**
   * Wiki commodity sync: no UEX match found; a new {@code WIKI_ONLY} row was inserted invisible.
   */
  CREATED_WIKI_ONLY,

  /** UEX item sync: an item had no Wiki cross-reference. */
  CREATED_UEX_ONLY,

  /**
   * Wiki commodity sync: the row's name matches the §4.3 "items masquerading as commodities" set
   * (Ace Interceptor Helmet, MedGel, …). Inserted invisible so an admin can decide.
   */
  LOOKS_LIKE_ITEM,

  /** Wiki commodity sync: the row was linked to a local material via the alias table. */
  LINKED_VIA_ALIAS,

  /** Wiki item sync (R4): joined an existing UEX row by shared {@code external_uuid}. */
  LINKED_VIA_UUID,

  /** Wiki commodity sync: the canonical name hit more than one UEX row; no link made. */
  MULTI_MATCH_AMBIGUOUS,

  /** Wiki blueprint sync (R4): an ingredient resource / item could not be resolved. */
  UNRESOLVED_INGREDIENT,

  /** Both sides (R4+): UEX and Wiki disagree on the manufacturer for the same UUID. */
  MANUFACTURER_MISMATCH,

  /**
   * Wiki manufacturer reconciliation: a Wiki manufacturer was first linked to an existing UEX
   * manufacturer row, stamping {@code scwiki_uuid} / {@code scwiki_code}.
   */
  MANUFACTURER_LINKED,

  /** Wiki item sync (R4): a UUID present in UEX is absent on the Wiki. */
  WIKI_MISSING,

  /** Vehicle backfill: a {@code ship_type.name} matched more than one UEX vehicle. */
  BACKFILL_AMBIGUOUS,

  /**
   * Wiki item backfill: a Wiki item with no UUID match was merged into a uuid-less {@code UEX_ONLY}
   * row found by exact {@code uex_slug} / name, setting its {@code external_uuid} and flipping it
   * to {@code BOTH} instead of inserting a duplicate.
   */
  LINKED_VIA_NAME,

  /**
   * Any sync: a once-per-run summary whose {@code detail} carries the run's tallies (visited /
   * created / updated / soft-deleted), emitted even when the run produced no other findings.
   */
  SYNC_RUN_SUMMARY,

  /**
   * KRT P4K Reader catalog import: a new row was seeded from the game's DataForge catalog for a
   * record matching no existing UEX / SC Wiki row, marked {@code source_systems = P4K}.
   */
  CREATED_FROM_P4K,

  /**
   * Wiki blueprint sync: a curated {@code blueprint.output_name} override (see {@code
   * BlueprintOutputNameOverrides}) did not fire because its {@code expectedWrongName} no longer
   * matches the upstream name, so the override is obsolete and should be removed. Emitted once per
   * obsolete override per run; {@code detail} carries the key, the current and the expected name.
   */
  BLUEPRINT_NAME_OVERRIDE_OBSOLETE
}

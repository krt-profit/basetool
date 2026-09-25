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

package de.greluc.krt.profit.basetool.backend.model.dto;

/**
 * Per-name resolution outcome of a blueprint import preview, deciding whether a row is
 * auto-confirmed or needs a manual pick.
 */
public enum BlueprintImportStatus {
  /** The external name normalized to an existing product key — an unambiguous direct match. */
  MATCHED,

  /** The external name resolved through a curated {@code blueprint_external_alias} (SCMDB) row. */
  MATCHED_BY_ALIAS,

  /** No exact / alias match, but one or more fuzzy candidates were found — needs a manual pick. */
  SUGGESTED,

  /** No match and no fuzzy candidate above the threshold — the user must search manually. */
  UNMATCHED,

  /** The name resolved to a product the caller already owns — nothing to add. */
  ALREADY_OWNED
}

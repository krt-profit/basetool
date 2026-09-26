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

package de.greluc.krt.profit.basetool.frontend.model.dto;

/**
 * Frontend mirror of the backend {@code BlueprintImportStatus}: the per-name resolution outcome
 * that groups rows in the import preview.
 */
public enum BlueprintImportStatus {
  /** The external name matched an existing product directly. */
  MATCHED,

  /** The external name resolved through a curated SCMDB alias. */
  MATCHED_BY_ALIAS,

  /** No exact / alias match, but fuzzy candidates were found — needs a manual pick. */
  SUGGESTED,

  /** No match and no fuzzy candidate — the user must search manually. */
  UNMATCHED,

  /** The name resolved to a product the caller already owns. */
  ALREADY_OWNED
}

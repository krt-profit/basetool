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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

/**
 * What a {@link P4kImportJob} does when its async worker runs: reconcile-and-report only, or
 * actually mutate the master data.
 */
public enum P4kImportJobKind {

  /** Dry run — reconcile every record and report the per-type counts, writing nothing. */
  PREVIEW,

  /**
   * Apply — enrich / reconcile the matching master-data rows and, when seeding is opted in, seed
   * brand-new rows for unmatched player-facing records.
   */
  APPLY
}

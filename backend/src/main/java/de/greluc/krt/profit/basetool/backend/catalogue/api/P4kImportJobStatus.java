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

/** Lifecycle status of a {@link P4kImportJob}, advanced by the async import worker. */
public enum P4kImportJobStatus {

  /** Enqueued and persisted, not yet picked up by the import worker. */
  PENDING,

  /** The worker is parsing the catalog and reconciling it against the master data. */
  RUNNING,

  /** Finished successfully; the per-type result is stored on the job row. */
  SUCCEEDED,

  /** Aborted by an error (empty/invalid catalog, constraint violation, ...); see the error msg. */
  FAILED
}

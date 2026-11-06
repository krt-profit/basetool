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

/** What the P4K sweep did with one kind of one run (REQ-P4K-011, REQ-P4K-012). */
public enum P4kSweepOutcome {

  /** The census was valid and the sweep ran, possibly stamping nothing. */
  SWEPT,

  /** The kind was {@code partial}: nothing stamped, nothing cleared. */
  SKIPPED_PARTIAL,

  /** The catalogue carried no record of the kind, so it is no census. */
  SKIPPED_EMPTY,

  /** A GUID occurred twice within the kind, which voids the census. */
  SKIPPED_DUPLICATE_GUID,

  /** The sweep would have stamped more than 5 % of the kind and the admin did not override. */
  SKIPPED_MASS_GUARD
}

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

/** The type of one row of a P4K run's change report (REQ-P4K-014). */
public enum P4kChangeType {

  /** The run created a Basetool row for the GUID (REQ-P4K-009). */
  ADDED,

  /** A catalogue record without a Basetool row, with its identifiers (REQ-P4K-008). */
  UNMATCHED,

  /** A review case the run found (REQ-P4K-016); {@code field_name} holds its reason. */
  REVIEW,

  /**
   * A matched row whose SC Wiki UUID differs from the P4K GUID (REQ-P4K-007); {@code old_value}
   * holds the Wiki UUID, {@code new_value} the GUID.
   */
  IDENTITY_MISMATCH,

  /**
   * A value of a closed set the import does not know, read as {@code null} (REQ-P4K-003); {@code
   * field_name} names the field, {@code new_value} holds the value.
   */
  UNKNOWN_VALUE
}

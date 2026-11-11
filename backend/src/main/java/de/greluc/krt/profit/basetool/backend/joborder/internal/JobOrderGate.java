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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

/**
 * The access a job-order edit demands of its caller, decided by {@link JobOrderAccessPolicy} on the
 * already-loaded order (ADR-0236 §6, ADR-0287).
 */
public enum JobOrderGate {

  /** The caller reaches the queue and holds the edit scope of the responsible unit. */
  EDIT,

  /** The caller is a current writer of the requesting unit (REQ-ORDERS-023). */
  REQUESTER,

  /** The responsible writer or a current Staffel writer may coordinate support. */
  SUPPORT,

  /** The responsible-unit writer may change the ordinary lifecycle status. */
  STATUS,

  /** Either current unit writer may cancel active work. */
  CANCEL,

  /**
   * Either current unit writer may reopen a cancellation, otherwise only the responsible writer.
   */
  SET_OPEN,

  /** Current responsible-unit authority permits recovery despite ineligible responsibility. */
  TRANSFER,

  /** The caller is an administrator independently of the queue-reading capability. */
  ADMIN_DELETE;

  boolean usesLifecycleVersion() {
    return this == STATUS || this == CANCEL || this == SET_OPEN;
  }
}

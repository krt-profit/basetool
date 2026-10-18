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

  /** The caller belongs to the requesting unit and nothing has been delivered (REQ-ORDERS-023). */
  REQUESTER
}

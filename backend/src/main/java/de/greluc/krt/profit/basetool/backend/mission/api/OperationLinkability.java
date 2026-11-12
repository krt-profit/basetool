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

package de.greluc.krt.profit.basetool.backend.mission.api;

/**
 * Whether the caller may link a mission to an operation, as the operation module answers it through
 * {@link OperationSummaryProvider#linkability(java.util.UUID)} (REQ-MISSION-034).
 */
public enum OperationLinkability {

  /** The operation exists and the caller may edit it, so a mission may be linked to it. */
  LINKABLE,

  /** The operation exists, but the caller may not edit it. */
  NOT_LINKABLE,

  /** No operation has the id. */
  NO_SUCH_OPERATION,

  /** The operation's payout inputs are frozen. */
  SETTLED
}

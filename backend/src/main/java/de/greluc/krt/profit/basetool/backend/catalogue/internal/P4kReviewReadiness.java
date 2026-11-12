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

/** Whether a review case has an applicable action and the context to perform it. */
public enum P4kReviewReadiness {
  /** At least one proposal can be applied after confirmation. */
  ACTIONABLE,
  /** The available facts need an admin decision or more evidence. */
  CHECK_REQUIRED,
  /** A prerequisite link or source record is missing. */
  BLOCKED,
  /** A confirmed repair is waiting for a compatible catalogue import. */
  WAITING_FOR_IMPORT
}

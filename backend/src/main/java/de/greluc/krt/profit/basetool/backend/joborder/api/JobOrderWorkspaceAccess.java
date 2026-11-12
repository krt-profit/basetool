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

package de.greluc.krt.profit.basetool.backend.joborder.api;

/** Offers the shared order workspace and unit-work views without conferring command authority. */
public interface JobOrderWorkspaceAccess {
  /**
   * Tests whether the current caller has an order-reading context.
   *
   * @return whether the shared workspace may be offered, including an empty permitted result
   */
  boolean canViewWorkspace();

  /**
   * Tests whether current units have a responsible or supporting work context.
   *
   * @return whether the unit-work view may be offered, independently of write authority
   */
  boolean canViewUnitWork();
}

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

import org.jetbrains.annotations.NotNull;

/**
 * The read API through which another module asks whether the current caller may see a job order;
 * the job-order module implements it with the rule its active-order lookup filters by
 * (REQ-ORG-003).
 */
public interface JobOrderVisibility {

  /**
   * Checks whether the order lies in the caller's tenancy read scope, the set the active-order
   * lookup offers as earmark targets; the assignee grant does not count.
   *
   * @param order the loaded order
   * @return {@code true} iff the caller sees the order within the tenancy scope
   */
  boolean canSeeJobOrderInScope(@NotNull JobOrder order);
}

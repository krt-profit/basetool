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

/** Separates common web order reading from the reduced external tenancy scope. */
public interface JobOrderVisibility {

  /**
   * Checks the complete permitted web order read, without granting stock or neighbouring data
   * writes.
   *
   * @param order the loaded order
   * @return whether the caller may read this order in the shared workspace
   */
  boolean canSeeJobOrder(@NotNull JobOrder order);

  /**
   * Checks the reduced external tenancy scope, independently of common web order reading.
   *
   * @param order the loaded order
   * @return {@code true} iff the caller sees the order within the tenancy scope
   */
  boolean canSeeJobOrderInScope(@NotNull JobOrder order);
}

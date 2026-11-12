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

import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** Supplies the current responsibility and recovery authority for order projections. */
public interface JobOrderAuthority {

  /**
   * Resolves page authority with a single hierarchy snapshot.
   *
   * @param orders the concrete orders to project
   * @return authority flags by order identity
   */
  Map<UUID, JobOrderAuthorityFlags> authorityFor(@NotNull Collection<JobOrder> orders);

  /**
   * Reports invalid responsibility against the current hierarchy and verified original boundary.
   *
   * @param order the order to inspect
   * @return whether only reading and valid recovery transfer are available
   */
  boolean responsibilityInvalid(@NotNull JobOrder order);

  /**
   * Reports current-unit recovery authority independent of general Profit access.
   *
   * @param order the order to inspect
   * @return whether the caller may transfer responsibility
   */
  boolean canTransferResponsibility(@NotNull JobOrder order);
}

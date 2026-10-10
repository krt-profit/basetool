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

package de.greluc.krt.profit.basetool.backend.inventory.api;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Decides what a caller may do with the Lager stock earmarked for a target the Lager does not own
 * (plan §5.3); implemented by the job-order module for its orders.
 */
public interface EarmarkTargetPolicy {

  /**
   * Checks whether the caller may edit the stock earmarked for a job order: a logistician or above
   * who may edit the order itself.
   *
   * @param jobOrderId the job order the stock is earmarked for
   * @return {@code true} iff the caller may edit that earmarked stock
   */
  boolean mayEditJobOrderEarmarks(@NotNull UUID jobOrderId);
}

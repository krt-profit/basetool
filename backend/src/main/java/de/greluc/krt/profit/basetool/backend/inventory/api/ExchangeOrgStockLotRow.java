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

/**
 * One org-stock lot: the shared rows of one unit's pool of one material or item at one location,
 * quality and stolen state, summed over every holder, with its earmarks (REQ-XCH-042).
 */
public interface ExchangeOrgStockLotRow extends ExchangeStockLotRow {

  /**
   * The unit whose pool holds the lot.
   *
   * @return the org unit id
   */
  UUID getUnitId();

  /**
   * The amount of the lot's rows earmarked to job orders.
   *
   * @return the summed earmark, {@code 0} when there is none
   */
  double getJobOrderEarmarked();

  /**
   * The amount of the lot's rows earmarked to missions.
   *
   * @return the summed earmark, {@code 0} when there is none
   */
  double getMissionEarmarked();
}

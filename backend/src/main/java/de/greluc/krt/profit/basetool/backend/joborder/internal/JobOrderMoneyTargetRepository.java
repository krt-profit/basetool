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

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** Persists the versioned target for a single aUEC order. */
interface JobOrderMoneyTargetRepository extends JpaRepository<JobOrderMoneyTarget, UUID> {

  @Query("SELECT t.amount FROM JobOrderMoneyTarget t WHERE t.orderId = :orderId")
  Optional<BigDecimal> amountFor(UUID orderId);

  /**
   * Reads the committed target version from the database, past any target the persistence context
   * holds.
   *
   * @param orderId the aUEC order
   * @return its target version, or empty when the order has no target
   */
  @Query("SELECT t.version FROM JobOrderMoneyTarget t WHERE t.orderId = :orderId")
  Optional<Long> versionFor(UUID orderId);
}

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

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Reads the account positions owned by an individual money order. */
interface JobOrderMoneyAccountHoldingRepository
    extends JpaRepository<JobOrderMoneyAccountHolding, UUID> {
  Optional<JobOrderMoneyAccountHolding> findByOrderIdAndBankAccountId(
      UUID orderId, UUID bankAccountId);

  Optional<JobOrderMoneyAccountHolding> findByOrderIdAndId(UUID orderId, UUID id);

  List<JobOrderMoneyAccountHolding> findAllByOrderIdOrderById(UUID orderId);
}

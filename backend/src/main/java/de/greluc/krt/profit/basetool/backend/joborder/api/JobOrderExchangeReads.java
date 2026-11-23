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
import org.jetbrains.annotations.Unmodifiable;

/**
 * Projects the member-independent exchange read of job orders (REQ-OXCH-003): the one projection
 * the exchange fingerprint hashes and the client read serves. It applies no access rule; the caller
 * decides which orders a member may read.
 */
public interface JobOrderExchangeReads {

  /**
   * Projects the orders in the caller's transaction, so every figure comes from one database state
   * when that transaction reads at {@code REPEATABLE READ}.
   *
   * @param orderIds the orders to project
   * @return each existing order's read by id; an id without an order is absent
   */
  @NotNull
  @Unmodifiable
  Map<UUID, JobOrderExchangeRead> project(@NotNull Collection<UUID> orderIds);
}

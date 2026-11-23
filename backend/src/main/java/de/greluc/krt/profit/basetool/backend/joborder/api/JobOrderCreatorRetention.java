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

import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Clears what an order keeps about its client create once the order left the exchange's client set
 * (REQ-OXCH-010): the creating member and the create's {@code Idempotency-Key}. The creating client
 * stays.
 */
public interface JobOrderCreatorRetention {

  /**
   * Clears the creating member and the create's key of the given orders that closed before an
   * instant; an order reopened meanwhile keeps both. Neither the order's version nor a section
   * version moves. Runs in the caller's transaction.
   *
   * @param orderIds the orders the caller found outside the client set
   * @param closedBefore the instant an order must have closed before
   * @return the number of orders cleared
   */
  int clearCreators(@NotNull Collection<UUID> orderIds, @NotNull Instant closedBefore);
}

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

import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * What a patch wipe removed from the Lager (REQ-PATCH-012).
 *
 * @param rows the number of rows deleted
 * @param holders the number of members who held them
 * @param jobOrders the job orders whose earmarks went with the rows
 */
public record StockWipeResult(int rows, int holders, @NotNull @Unmodifiable Set<UUID> jobOrders) {

  /** Copies the job orders, so the result never shares a caller's set. */
  public StockWipeResult {
    jobOrders = Set.copyOf(jobOrders);
  }
}

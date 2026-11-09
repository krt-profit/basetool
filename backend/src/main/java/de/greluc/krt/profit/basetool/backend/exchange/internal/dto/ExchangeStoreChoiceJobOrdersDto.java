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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The active job orders the member sees that need one material, as {@code
 * store-choice-job-orders.schema.json} describes it (REQ-XCH-054).
 *
 * @param items the job orders in the order the web's lookup lists them
 */
public record ExchangeStoreChoiceJobOrdersDto(@NotNull @Unmodifiable List<JobOrder> items) {

  /**
   * One job order.
   *
   * @param id the order's id, as a store line's {@code jobOrder} names it
   * @param number the order's running number, or {@code null} when it has none yet
   * @param status {@code OPEN} or {@code IN_PROGRESS}
   */
  @Schema(name = "StoreChoiceJobOrder")
  public record JobOrder(@NotNull UUID id, @Nullable Integer number, @NotNull String status) {}
}

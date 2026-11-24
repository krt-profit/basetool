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
import java.time.Instant;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * A snapshot or feed page of the member's job-order set (REQ-OXCH-003); a snapshot's last page
 * hands over to the feed.
 *
 * @param items the orders of the page: on a snapshot page ascending by id, on a feed page in the
 *     order their changes were announced
 * @param removed the orders that left the set, each without the reason; always empty on a snapshot
 *     page
 * @param nextCursor the cursor of the next page, a feed cursor on a snapshot's last page and on
 *     every feed page
 * @param hasMore whether the snapshot or the feed continues
 */
public record ExchangeJobOrderPageDto(
    @NotNull @Unmodifiable List<ExchangeJobOrderDto> items,
    @NotNull @Unmodifiable List<Removed> removed,
    @NotNull String nextCursor,
    boolean hasMore) {

  /**
   * An order that left the member's set: a lost relation, the end of its window and a deletion look
   * alike.
   *
   * @param key the order's id
   * @param removedAt when its latest change was recorded
   */
  @Schema(name = "ExchangeJobOrderRemoved")
  public record Removed(@NotNull String key, @NotNull Instant removedAt) {}
}

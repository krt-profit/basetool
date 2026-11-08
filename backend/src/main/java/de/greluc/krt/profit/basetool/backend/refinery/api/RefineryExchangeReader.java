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

package de.greluc.krt.profit.basetool.backend.refinery.api;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Unmodifiable;

/**
 * A member's own refinery orders as the exchange feed reads them (REQ-XCH-051). Reads run in the
 * caller's transaction and apply no scope beyond the owner: the exchange serves own orders only.
 */
public interface RefineryExchangeReader {

  /**
   * Returns one snapshot page of the owner's open and running orders, ordered by id.
   *
   * @param owner the owning member
   * @param after the last order delivered, or the zero id for the first page
   * @param limit the most orders to return
   * @return the orders in {@code OPEN} or {@code IN_PROGRESS} with an id after {@code after}
   */
  @NotNull
  @Unmodifiable
  List<RefineryExchangeOrder> openOrders(@NotNull UUID owner, @NotNull UUID after, int limit);

  /**
   * Returns the owner's orders with the given ids, whatever their status.
   *
   * @param owner the owning member
   * @param ids the order ids
   * @return the orders among {@code ids} the member owns now, in no particular order
   */
  @NotNull
  @Unmodifiable
  List<RefineryExchangeOrder> orders(@NotNull UUID owner, @NotNull Collection<UUID> ids);
}

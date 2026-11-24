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

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * The caps on the aUEC orders a member creates through clients and the member's own figures against
 * them (REQ-OXCH-009); orders created in the Basetool count for neither.
 */
public interface JobOrderClientCreateCaps {

  /**
   * Reads both caps and the member's own client-created orders against them, so a client can tell
   * before the member fills in the form whether a create would be refused.
   *
   * @param member the member's user id
   * @return the caps and the member's open orders and creates of the current UTC day
   */
  @NotNull
  Limits limits(@NotNull UUID member);

  /**
   * Both caps and one member's figures against them.
   *
   * @param openOrderCap the open or in-progress client-created orders at which a create is refused
   * @param openOrders the member's open or in-progress orders created through any client
   * @param dailyCreateCap the client creates within a UTC day at which a create is refused
   * @param createdToday the orders the member created through clients since the start of the UTC
   *     day, whatever their status since
   */
  record Limits(int openOrderCap, long openOrders, int dailyCreateCap, long createdToday) {}
}

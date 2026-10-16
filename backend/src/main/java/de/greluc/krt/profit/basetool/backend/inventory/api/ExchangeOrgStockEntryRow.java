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

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * One shared Lager row of an org-stock lot with what the web's entry row shows of it (REQ-XCH-043).
 *
 * @param lotKey the org-stock lot key of the lot the row belongs to
 * @param id the row id
 * @param version the row's optimistic-lock version
 * @param holderId the member who holds the row
 * @param holderName the name the web shows for the holder
 * @param amount the row's amount
 * @param note the row's note, or {@code null} when it has none
 * @param jobOrders the row's job-order earmarks
 * @param missions the row's mission earmarks
 */
public record ExchangeOrgStockEntryRow(
    @NotNull String lotKey,
    @NotNull UUID id,
    long version,
    @NotNull UUID holderId,
    @NotNull String holderName,
    double amount,
    @Nullable String note,
    @NotNull @Unmodifiable List<JobOrder> jobOrders,
    @NotNull @Unmodifiable List<Mission> missions) {

  /**
   * One job-order earmark of a row.
   *
   * @param jobOrderId the order
   * @param displayId the order's number, or {@code null} when the order is unknown
   * @param amount the earmarked amount
   * @param delivered whether the earmarked amount was delivered to the order
   */
  public record JobOrder(
      @NotNull UUID jobOrderId, @Nullable Integer displayId, double amount, boolean delivered) {}

  /**
   * One mission earmark of a row.
   *
   * @param missionId the mission
   * @param name the mission's name, or {@code null} when the mission is unknown
   * @param amount the earmarked amount
   */
  public record Mission(@NotNull UUID missionId, @Nullable String name, double amount) {}
}

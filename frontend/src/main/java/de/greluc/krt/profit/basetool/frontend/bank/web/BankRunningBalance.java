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

package de.greluc.krt.profit.basetool.frontend.bank.web;

import de.greluc.krt.profit.basetool.frontend.bank.model.BankBalancePointDto;
import de.greluc.krt.profit.basetool.frontend.bank.model.BankBalanceSeriesDto;
import de.greluc.krt.profit.basetool.frontend.bank.model.BankBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * Derives the "Saldo nach Buchung" column of an account's booking page: the account balance right
 * after each posting, walked backwards from the balance after the page's newest posting.
 *
 * <p>The anchor balance is read from the account's balance series over the single instant of the
 * newest posting, which the backend answers with the balance including every posting at that
 * instant.
 */
public final class BankRunningBalance {

  /** Utility class — not instantiable. */
  private BankRunningBalance() {}

  /**
   * Returns the instant of the newest posting of a newest-first booking page.
   *
   * @param page the booking page, or {@code null} when the history failed to load
   * @return the newest posting's instant, or {@code null} when the page is absent or empty
   */
  @Nullable
  @Contract("null -> null")
  public static Instant anchorInstant(@Nullable PageResponse<BankBookingDto> page) {
    if (page == null || page.content() == null || page.content().isEmpty()) {
      return null;
    }
    return page.content().getFirst().createdAt();
  }

  /**
   * Reads the anchor balance from a balance series requested over the anchor instant alone.
   *
   * @param series the balance series, or {@code null} when it failed to load
   * @return the last point's balance, or {@code null} when the series carries no point
   */
  @Nullable
  @Contract("null -> null")
  public static BigDecimal lastBalance(@Nullable BankBalanceSeriesDto series) {
    if (series == null || series.points() == null || series.points().isEmpty()) {
      return null;
    }
    BankBalancePointDto last = series.points().getLast();
    return last == null ? null : last.balance();
  }

  /**
   * Maps each posting of a newest-first booking page to the account balance right after it.
   *
   * @param rows the page's postings, newest first
   * @param balanceAfterNewest the balance after the newest posting, or {@code null} when unknown
   * @return posting id to balance after that posting; empty when the anchor is unknown or the page
   *     is empty
   */
  @NotNull
  @Unmodifiable
  public static Map<UUID, BigDecimal> balancesAfter(
      @Nullable List<BankBookingDto> rows, @Nullable BigDecimal balanceAfterNewest) {
    if (rows == null || rows.isEmpty() || balanceAfterNewest == null) {
      return Map.of();
    }
    Map<UUID, BigDecimal> result = new HashMap<>();
    BigDecimal running = balanceAfterNewest;
    for (BankBookingDto row : rows) {
      if (row == null) {
        continue;
      }
      if (row.postingId() != null) {
        result.put(row.postingId(), running);
      }
      if (row.amount() != null) {
        running = running.subtract(row.amount());
      }
    }
    return Map.copyOf(result);
  }
}

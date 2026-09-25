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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.model.projection.BankPostingSlice;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

/**
 * Derives the bank's 30-day trend figures (REQ-BANK-016): window cutoff, net delta and daily
 * end-of-day balance series, shared by the bank dashboard and the org-unit balance page.
 *
 * <p>Pure computation; no repository or security access.
 */
public final class BankTrendCalculator {

  /** Length of the trend window in days (REQ-BANK-016). */
  public static final int WINDOW_DAYS = 30;

  /** Utility class — not instantiable. */
  private BankTrendCalculator() {}

  /**
   * Returns the inclusive start of the trend window, {@value #WINDOW_DAYS} days before now.
   *
   * @return the window start instant
   */
  @NotNull
  public static Instant windowCutoff() {
    return Instant.now().minusSeconds((long) WINDOW_DAYS * 24 * 3600);
  }

  /**
   * The net balance change inside the window: the signed sum of the slice amounts. Equals the
   * dashboard/page "± 30 days" figure.
   *
   * @param slices the account's posting slices inside the window (may be empty)
   * @return the net change, {@link BigDecimal#ZERO} when there are no slices
   */
  @NotNull
  public static BigDecimal windowDelta(@NotNull List<BankPostingSlice> slices) {
    BigDecimal delta = BigDecimal.ZERO;
    for (BankPostingSlice slice : slices) {
      delta = delta.add(slice.amount());
    }
    return delta;
  }

  /**
   * Derives the end-of-day balance series of the last {@value #WINDOW_DAYS} days, starting at
   * {@code balance - delta} and ending at the current balance. Days are bucketed in UTC.
   *
   * @param balance the account's current balance
   * @param delta the net change inside the window (see {@link #windowDelta(List)})
   * @param slices the account's posting slices inside the window
   * @return {@value #WINDOW_DAYS} end-of-day balances, oldest first
   */
  @NotNull
  public static List<BigDecimal> sparkline(
      @NotNull BigDecimal balance,
      @NotNull BigDecimal delta,
      @NotNull List<BankPostingSlice> slices) {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    Map<LocalDate, BigDecimal> dailyNet =
        slices.stream()
            .collect(
                Collectors.groupingBy(
                    slice -> slice.createdAt().atZone(ZoneOffset.UTC).toLocalDate(),
                    Collectors.reducing(
                        BigDecimal.ZERO, BankPostingSlice::amount, BigDecimal::add)));
    List<BigDecimal> series = new ArrayList<>(WINDOW_DAYS);
    BigDecimal running = balance.subtract(delta);
    for (int i = WINDOW_DAYS - 1; i >= 0; i--) {
      LocalDate day = today.minusDays(i);
      running = running.add(dailyNet.getOrDefault(day, BigDecimal.ZERO));
      series.add(running);
    }
    return series;
  }
}

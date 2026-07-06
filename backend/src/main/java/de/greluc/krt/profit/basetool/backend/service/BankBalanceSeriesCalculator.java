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

import de.greluc.krt.profit.basetool.backend.model.dto.BankBalancePointDto;
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
 * Derives the account balance-over-time series the detail page renders as an inline SVG line chart
 * (REQ-BANK-049) from a period's opening balance and its posting slices. It walks the ledger one
 * UTC calendar day at a time — starting at the balance <em>before</em> the period and adding each
 * day's net — so the value plotted at day {@code D} is the end-of-day-{@code D} balance and the
 * last point equals the balance at the period end. When the period spans more than {@link
 * #MAX_POINTS} days the daily series is evenly downsampled (every real sampled point stays an
 * actual end-of-day balance, and the final point is always kept) so the polyline stays a sane size
 * for any range up to "Gesamt".
 *
 * <p>Pure computation only, mirroring {@link BankTrendCalculator}: it never touches the security
 * context or a repository, so it stays outside the {@code BankSecurityService}/org-unit-scope
 * concerns entirely and is trivially unit-testable.
 */
public final class BankBalanceSeriesCalculator {

  /**
   * Upper bound on the number of points returned. A 30/90-day range stays daily (≤ 90 points); a
   * one-year or "Gesamt" range is downsampled to keep the SVG polyline small without losing the
   * shape of the trend.
   */
  public static final int MAX_POINTS = 180;

  /** Utility class — not instantiable. */
  private BankBalanceSeriesCalculator() {}

  /**
   * Builds the end-of-day balance series over {@code [from, to]} (inclusive, bucketed in UTC).
   *
   * @param opening the account balance immediately before {@code from} (from {@code
   *     BankPostingRepository#accountBalanceBefore})
   * @param slices the account's posting slices inside {@code [from, to]}, any order
   * @param from the inclusive period start
   * @param to the inclusive period end; an inverted period yields an empty series
   * @return the end-of-day balances, oldest first, downsampled to at most {@link #MAX_POINTS}
   *     points
   */
  @NotNull
  public static List<BankBalancePointDto> compute(
      @NotNull BigDecimal opening,
      @NotNull List<BankPostingSlice> slices,
      @NotNull Instant from,
      @NotNull Instant to) {
    if (from.isAfter(to)) {
      return List.of();
    }
    LocalDate startDate = from.atZone(ZoneOffset.UTC).toLocalDate();
    LocalDate endDate = to.atZone(ZoneOffset.UTC).toLocalDate();
    Map<LocalDate, BigDecimal> dailyNet =
        slices.stream()
            .collect(
                Collectors.groupingBy(
                    slice -> slice.createdAt().atZone(ZoneOffset.UTC).toLocalDate(),
                    Collectors.reducing(
                        BigDecimal.ZERO, BankPostingSlice::amount, BigDecimal::add)));
    List<BankBalancePointDto> full = new ArrayList<>();
    BigDecimal running = opening;
    for (LocalDate day = startDate; !day.isAfter(endDate); day = day.plusDays(1)) {
      running = running.add(dailyNet.getOrDefault(day, BigDecimal.ZERO));
      full.add(new BankBalancePointDto(day.atStartOfDay(ZoneOffset.UTC).toInstant(), running));
    }
    return downsample(full);
  }

  /**
   * Evenly thins a daily series down to at most {@link #MAX_POINTS} points, always retaining the
   * final point so the chart's last value stays the true period-end balance.
   *
   * @param full the full daily series, oldest first
   * @return the same list when already within the cap, else an evenly strided subset plus the last
   *     point
   */
  @NotNull
  private static List<BankBalancePointDto> downsample(@NotNull List<BankBalancePointDto> full) {
    int size = full.size();
    if (size <= MAX_POINTS) {
      return full;
    }
    int stride = (size + MAX_POINTS - 1) / MAX_POINTS;
    List<BankBalancePointDto> out = new ArrayList<>();
    for (int i = 0; i < size; i += stride) {
      out.add(full.get(i));
    }
    BankBalancePointDto last = full.get(size - 1);
    if (out.isEmpty() || !out.get(out.size() - 1).equals(last)) {
      out.add(last);
    }
    return out;
  }
}

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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.projection.BankPostingSlice;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BankTrendCalculator} (REQ-BANK-016/021): the 30-day end-of-day balance
 * sparkline and the signed net window delta the bank dashboard and org-unit balance page share. The
 * load-bearing invariants covered are the fixed {@link BankTrendCalculator#WINDOW_DAYS} series
 * length, the reconciliation of the last plotted point back to the current balance (series opens at
 * {@code balance - delta} and walks daily nets forward), and the UTC day-bucketing that merges two
 * postings of the same calendar day into one step.
 */
class BankTrendCalculatorTest {

  /** Fixed account id for the slices; the calculator ignores it, so any value works. */
  private static final UUID ACCOUNT_ID = UUID.randomUUID();

  /**
   * Builds a posting slice on a UTC day {@code daysAgo} before {@code today}, at midday so the
   * booking instant unambiguously buckets into that calendar day.
   *
   * @param today the UTC "today" the calculator will bucket against
   * @param daysAgo how many whole days before {@code today} the posting falls
   * @param amount the signed amount
   * @return the slice
   */
  private static BankPostingSlice sliceDaysAgo(LocalDate today, int daysAgo, String amount) {
    Instant createdAt =
        today.minusDays(daysAgo).atStartOfDay(ZoneOffset.UTC).plusHours(12).toInstant();
    return new BankPostingSlice(ACCOUNT_ID, createdAt, new BigDecimal(amount));
  }

  /**
   * Builds a posting slice at an explicit instant; used where only the summed amount matters.
   *
   * @param instant the ISO-8601 booking instant
   * @param amount the signed amount
   * @return the slice
   */
  private static BankPostingSlice slice(String instant, String amount) {
    return new BankPostingSlice(ACCOUNT_ID, Instant.parse(instant), new BigDecimal(amount));
  }

  /**
   * The series always holds exactly {@link BankTrendCalculator#WINDOW_DAYS} points, opens at the
   * window-start balance ({@code balance - delta}) and reconciles: its last point equals the
   * current balance once every daily net has been walked forward.
   */
  @Test
  void sparkline_hasThirtyPointsAndLastEqualsBalance() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    BigDecimal balance = new BigDecimal("1000");
    List<BankPostingSlice> slices =
        List.of(sliceDaysAgo(today, 5, "500"), sliceDaysAgo(today, 2, "-200"));
    BigDecimal delta = BankTrendCalculator.windowDelta(slices);

    List<BigDecimal> series = BankTrendCalculator.sparkline(balance, delta, slices);

    assertThat(series).hasSize(BankTrendCalculator.WINDOW_DAYS);
    assertThat(series.get(0)).isEqualByComparingTo(balance.subtract(delta));
    assertThat(series.getLast()).isEqualByComparingTo(balance);
  }

  /**
   * Two postings on the same UTC calendar day collapse into a single step whose size is their sum,
   * rather than producing two separate daily jumps.
   */
  @Test
  void sparkline_mergesSameUtcDayNets() {
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    BigDecimal balance = new BigDecimal("1000");
    List<BankPostingSlice> slices =
        List.of(sliceDaysAgo(today, 3, "100"), sliceDaysAgo(today, 3, "50"));
    BigDecimal delta = BankTrendCalculator.windowDelta(slices);

    List<BigDecimal> series = BankTrendCalculator.sparkline(balance, delta, slices);

    // Day (today - k) sits at index WINDOW_DAYS - 1 - k; the day before it is one index lower.
    int dayIndex = BankTrendCalculator.WINDOW_DAYS - 1 - 3;
    BigDecimal step = series.get(dayIndex).subtract(series.get(dayIndex - 1));
    assertThat(step).isEqualByComparingTo("150");
    assertThat(series.getLast()).isEqualByComparingTo(balance);
  }

  /** The window delta is the signed sum of all slice amounts. */
  @Test
  void windowDelta_sumsSignedAmounts() {
    List<BankPostingSlice> slices =
        List.of(
            slice("2026-06-01T10:00:00Z", "500"),
            slice("2026-06-02T09:00:00Z", "-200"),
            slice("2026-06-03T08:00:00Z", "30"));

    assertThat(BankTrendCalculator.windowDelta(slices)).isEqualByComparingTo("330");
  }

  /** With no slices the window delta is zero. */
  @Test
  void windowDelta_emptyIsZero() {
    assertThat(BankTrendCalculator.windowDelta(List.of())).isEqualByComparingTo("0");
  }
}

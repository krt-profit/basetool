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

import de.greluc.krt.profit.basetool.backend.model.dto.BankBalancePointDto;
import de.greluc.krt.profit.basetool.backend.model.projection.BankPostingSlice;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BankBalanceSeriesCalculator} (REQ-BANK-049): the end-of-day walk from the
 * opening balance, the flat-line and inverted-period edge cases, and the downsampling cap for long
 * ranges.
 */
class BankBalanceSeriesCalculatorTest {

  /** Fixed account id for the slices; the calculator ignores it, so any value works. */
  private static final UUID ACCOUNT_ID = UUID.randomUUID();

  /**
   * Builds a posting slice at an instant with a signed amount.
   *
   * @param instant the ISO-8601 booking instant
   * @param amount the signed amount
   * @return the slice
   */
  private static BankPostingSlice slice(String instant, String amount) {
    return new BankPostingSlice(ACCOUNT_ID, Instant.parse(instant), new BigDecimal(amount));
  }

  /**
   * Walks the daily nets onto the opening balance so each point is that day's end-of-day balance.
   */
  @Test
  void compute_walksEndOfDayBalancesFromOpening() {
    List<BankBalancePointDto> series =
        BankBalanceSeriesCalculator.compute(
            new BigDecimal("1000"),
            List.of(slice("2026-06-01T10:00:00Z", "500"), slice("2026-06-02T09:00:00Z", "-200")),
            Instant.parse("2026-06-01T00:00:00Z"),
            Instant.parse("2026-06-03T23:59:59Z"));

    assertThat(series).hasSize(3);
    assertThat(series.get(0).balance()).isEqualByComparingTo("1500");
    assertThat(series.get(1).balance()).isEqualByComparingTo("1300");
    assertThat(series.get(2).balance()).isEqualByComparingTo("1300");
  }

  /** With no postings in the window the series is a flat line at the opening balance. */
  @Test
  void compute_emptySlices_flatLineAtOpening() {
    List<BankBalancePointDto> series =
        BankBalanceSeriesCalculator.compute(
            new BigDecimal("750"),
            List.of(),
            Instant.parse("2026-06-01T00:00:00Z"),
            Instant.parse("2026-06-02T00:00:00Z"));

    assertThat(series).hasSize(2);
    assertThat(series).allSatisfy(p -> assertThat(p.balance()).isEqualByComparingTo("750"));
  }

  /** An inverted period (from after to) yields an empty series rather than throwing. */
  @Test
  void compute_invertedPeriod_isEmpty() {
    assertThat(
            BankBalanceSeriesCalculator.compute(
                BigDecimal.ZERO,
                List.of(),
                Instant.parse("2026-06-05T00:00:00Z"),
                Instant.parse("2026-06-01T00:00:00Z")))
        .isEmpty();
  }

  /**
   * A range far longer than the cap is downsampled but always keeps the final period-end balance.
   */
  @Test
  void compute_downsamplesLongRangeToTheCap() {
    List<BankBalancePointDto> series =
        BankBalanceSeriesCalculator.compute(
            new BigDecimal("42"),
            List.of(),
            Instant.parse("2024-01-01T00:00:00Z"),
            Instant.parse("2026-03-11T00:00:00Z"));

    assertThat(series.size()).isLessThanOrEqualTo(BankBalanceSeriesCalculator.MAX_POINTS);
    assertThat(series).isNotEmpty();
    assertThat(series.getLast().balance()).isEqualByComparingTo("42");
  }
}

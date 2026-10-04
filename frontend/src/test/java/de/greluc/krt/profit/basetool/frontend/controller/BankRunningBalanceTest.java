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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.frontend.model.dto.BankBalancePointDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBalanceSeriesDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BankBookingDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

/**
 * Covers the "Saldo nach Buchung" derivation of the account detail pages, and the booking-history
 * segment a resolved period maps to.
 */
class BankRunningBalanceTest {

  /**
   * A posting of the given amount at the given instant.
   *
   * @param id the posting id
   * @param amount the signed amount
   * @param at the posting instant
   * @return the booking row
   */
  private static @NotNull BankBookingDto posting(
      @NotNull UUID id, @NotNull String amount, @NotNull String at) {
    return new BankBookingDto(
        id,
        UUID.randomUUID(),
        "DEPOSIT",
        new BigDecimal(amount),
        "alpha",
        null,
        null,
        null,
        Instant.parse(at),
        null,
        null,
        null,
        null,
        false,
        BigDecimal.ZERO,
        null,
        null);
  }

  /** Walks the page backwards from the balance after its newest posting. */
  @Test
  void balancesAfterWalksBackFromTheNewestPosting() {
    UUID newest = UUID.randomUUID();
    UUID middle = UUID.randomUUID();
    UUID oldest = UUID.randomUUID();
    List<BankBookingDto> rows =
        List.of(
            posting(newest, "240000", "2026-10-02T10:00:00Z"),
            posting(middle, "-62400", "2026-10-01T10:00:00Z"),
            posting(oldest, "300000", "2026-09-29T10:00:00Z"));

    Map<UUID, BigDecimal> after = BankRunningBalance.balancesAfter(rows, new BigDecimal("3148200"));

    assertThat(after.get(newest)).isEqualByComparingTo("3148200");
    assertThat(after.get(middle)).isEqualByComparingTo("2908200");
    assertThat(after.get(oldest)).isEqualByComparingTo("2970600");
  }

  /** Without an anchor balance or without rows the column stays empty. */
  @Test
  void balancesAfterIsEmptyWithoutAnchorOrRows() {
    List<BankBookingDto> rows = List.of(posting(UUID.randomUUID(), "1", "2026-10-02T10:00:00Z"));

    assertThat(BankRunningBalance.balancesAfter(rows, null)).isEmpty();
    assertThat(BankRunningBalance.balancesAfter(List.of(), BigDecimal.TEN)).isEmpty();
    assertThat(BankRunningBalance.balancesAfter(null, BigDecimal.TEN)).isEmpty();
  }

  /** The anchor is the newest posting's instant; an absent or empty page has none. */
  @Test
  void anchorInstantIsTheNewestPostingInstant() {
    PageResponse<BankBookingDto> page =
        new PageResponse<>(
            List.of(
                posting(UUID.randomUUID(), "5", "2026-10-02T10:00:00.123456Z"),
                posting(UUID.randomUUID(), "5", "2026-10-01T10:00:00Z")),
            0,
            50,
            2L,
            1,
            List.of());

    assertThat(BankRunningBalance.anchorInstant(page))
        .isEqualTo(Instant.parse("2026-10-02T10:00:00.123456Z"));
    assertThat(BankRunningBalance.anchorInstant(null)).isNull();
    PageResponse<BankBookingDto> empty = new PageResponse<>(List.of(), 0, 50, 0L, 0, List.of());
    assertThat(BankRunningBalance.anchorInstant(empty)).isNull();
  }

  /** The anchor balance is the last point of the series; an empty series yields none. */
  @Test
  void lastBalanceReadsTheLastPoint() {
    BankBalanceSeriesDto series =
        new BankBalanceSeriesDto(
            List.of(
                new BankBalancePointDto(Instant.parse("2026-10-01T00:00:00Z"), BigDecimal.ONE),
                new BankBalancePointDto(Instant.parse("2026-10-02T00:00:00Z"), BigDecimal.TEN)),
            null);

    assertThat(BankRunningBalance.lastBalance(series)).isEqualByComparingTo("10");
    assertThat(BankRunningBalance.lastBalance(new BankBalanceSeriesDto(List.of(), null))).isNull();
    assertThat(BankRunningBalance.lastBalance(null)).isNull();
  }

  /** A period ending today maps to its preset segment, anything else to the custom range. */
  @Test
  void historyPresetNamesTheMatchingSegment() {
    LocalDate today = LocalDate.parse("2026-10-03");

    assertThat(BankAccountDetailSupport.historyPreset(period("2026-09-03", "2026-10-03"), today))
        .isEqualTo("30d");
    assertThat(BankAccountDetailSupport.historyPreset(period("2026-07-05", "2026-10-03"), today))
        .isEqualTo("90d");
    assertThat(BankAccountDetailSupport.historyPreset(period("2026-09-01", "2026-10-03"), today))
        .isEqualTo("custom");
    assertThat(BankAccountDetailSupport.historyPreset(period("2026-08-02", "2026-09-01"), today))
        .isEqualTo("custom");
  }

  /**
   * Resolves a booking-history period from two ISO dates.
   *
   * @param from the start date
   * @param to the end date
   * @return the resolved period
   */
  private static BankAccountDetailSupport.HistoryPeriod period(
      @NotNull String from, @NotNull String to) {
    return BankAccountDetailSupport.resolveHistoryPeriod(from, to);
  }
}

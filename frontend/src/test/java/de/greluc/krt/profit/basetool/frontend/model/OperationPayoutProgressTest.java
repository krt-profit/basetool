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

package de.greluc.krt.profit.basetool.frontend.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import de.greluc.krt.profit.basetool.frontend.model.dto.OperationPayoutDto;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Verifies how {@link OperationPayoutProgress} folds an operation's payout rows. */
class OperationPayoutProgressTest {

  /**
   * One payout row.
   *
   * @param preference the payout preference
   * @param amount the payout amount, may be {@code null}
   * @param paidOut whether the row is marked paid out
   * @return the row
   */
  private static OperationPayoutDto row(
      PayoutPreference preference, String amount, boolean paidOut) {
    return new OperationPayoutDto(
        "p",
        "Pilot",
        25.0,
        preference,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        amount == null ? null : new BigDecimal(amount),
        paidOut,
        null,
        null);
  }

  /** No rows, or none at all, is an all-zero progress. */
  @Test
  void ofNullOrEmpty_isAllZero() {
    OperationPayoutProgress fromNull = OperationPayoutProgress.of(null);
    OperationPayoutProgress fromEmpty = OperationPayoutProgress.of(List.of());

    assertEquals(fromNull, fromEmpty);
    assertEquals(0, fromNull.participants());
    assertEquals(0, fromNull.paidOut());
    assertEquals(0, fromNull.donors());
    assertEquals(0.0, fromNull.paidPercent());
    assertEquals(0, BigDecimal.ZERO.compareTo(fromNull.openAmount()));
    assertEquals(0, BigDecimal.ZERO.compareTo(fromNull.totalAmount()));
  }

  /** Paid rows leave the open amount, donors are counted, a missing amount counts as zero. */
  @Test
  void of_countsPaidDonorsAndSumsOpenAndTotal() {
    OperationPayoutProgress progress =
        OperationPayoutProgress.of(
            Arrays.asList(
                row(PayoutPreference.PAYOUT, "600000", true),
                row(PayoutPreference.DONATE, "412880", false),
                row(PayoutPreference.DONATE, null, false),
                row(PayoutPreference.PAYOUT, "100", false)));

    assertEquals(4, progress.participants());
    assertEquals(1, progress.paidOut());
    assertEquals(2, progress.donors());
    assertEquals(25.0, progress.paidPercent());
    assertEquals(0, new BigDecimal("412980").compareTo(progress.openAmount()));
    assertEquals(0, new BigDecimal("1012980").compareTo(progress.totalAmount()));
  }
}

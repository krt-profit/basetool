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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pins the shared Bank report counterparty cells independently of holder visibility. */
class BankCounterpartyCellTest {
  @Test
  void depositsAndWithdrawalsPreserveTheNameUnitAndErasureRules() {
    for (BankTransactionType type :
        List.of(BankTransactionType.DEPOSIT, BankTransactionType.WITHDRAWAL)) {
      assertThat(cell(row(type, "member", "Alpha"))).isEqualTo("member (Alpha)");
      assertThat(cell(row(type, "member", null))).isEqualTo("member");
      assertThat(cell(row(type, null, null))).isEmpty();
      assertThat(cell(row(type, HandleAnonymisation.SENTINEL, "Alpha")))
          .isEqualTo("erased (Alpha)");
    }
  }

  @Test
  void transfersSelectTheOtherPostingAndUnrelatedKindsStayEmpty() {
    BankBookingRow row = row(BankTransactionType.TRANSFER, null, null);
    List<BankCounterLeg> legs =
        List.of(
            new BankCounterLeg(
                row.transactionId(),
                row.postingId(),
                UUID.randomUUID(),
                "OWN",
                "own",
                BigDecimal.ONE),
            new BankCounterLeg(
                row.transactionId(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "OTHER",
                "other",
                BigDecimal.ONE.negate()));
    assertThat(BankPdfFormat.counterpartyCell(row, Map.of(row.transactionId(), legs), "erased"))
        .isEqualTo("OTHER");
    assertThat(cell(row)).isEmpty();
    assertThat(cell(row(BankTransactionType.REVERSAL, "ignored", "ignored"))).isEmpty();
  }

  private static String cell(BankBookingRow row) {
    return BankPdfFormat.counterpartyCell(row, Map.of(), "erased");
  }

  private static BankBookingRow row(BankTransactionType type, String handle, String orgUnit) {
    return new BankBookingRow(
        UUID.randomUUID(),
        UUID.randomUUID(),
        type,
        BigDecimal.ONE,
        null,
        null,
        null,
        Instant.EPOCH,
        null,
        BigDecimal.ZERO,
        handle,
        orgUnit,
        null);
  }
}

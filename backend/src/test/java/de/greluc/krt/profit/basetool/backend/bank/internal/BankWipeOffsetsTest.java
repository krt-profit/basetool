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

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The currency wipe's offsets (REQ-PATCH-041, ADR-0384): each balance of the live time is offset
 * exactly, unless that would take the current balance below zero; then the offset stops at zero.
 */
class BankWipeOffsetsTest {

  private static final UUID KEPT = UUID.randomUUID();
  private static final UUID CLAMPED = UUID.randomUUID();
  private static final UUID ALREADY_NEGATIVE = UUID.randomUUID();
  private static final UUID OWED = UUID.randomUUID();

  @Test
  void aBalanceThatStillHoldsItsLiveTimeAmountIsOffsetExactly() {
    BankWipeOffsets offsets =
        BankWipeOffsets.of(Map.of(KEPT, amount("1000")), Map.of(KEPT, amount("1200")));

    assertThat(offsets.offsets()).containsExactlyEntriesOf(Map.of(KEPT, amount("1000")));
    assertThat(offsets.clamped()).isZero();
    assertThat(offsets.total()).isEqualByComparingTo("1000");
  }

  @Test
  void aBalanceThatSpentMoreThanItGotSinceTheLiveTimeStopsAtZero() {
    BankWipeOffsets offsets =
        BankWipeOffsets.of(
            Map.of(CLAMPED, amount("1000"), ALREADY_NEGATIVE, amount("500")),
            Map.of(CLAMPED, amount("196"), ALREADY_NEGATIVE, amount("-50")));

    assertThat(offsets.offsets())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(CLAMPED, amount("196"), ALREADY_NEGATIVE, BigDecimal.ZERO));
    assertThat(offsets.clamped()).isEqualTo(2);
    assertThat(offsets.total()).isEqualByComparingTo("196");
  }

  @Test
  void aNegativeLiveTimeBalanceIsOffsetExactly() {
    BankWipeOffsets offsets =
        BankWipeOffsets.of(Map.of(OWED, amount("-300")), Map.of(OWED, amount("-100")));

    assertThat(offsets.offsets()).containsExactlyEntriesOf(Map.of(OWED, amount("-300")));
    assertThat(offsets.clamped()).isZero();
  }

  @Test
  void aBalanceWithoutAnyCurrentPostingCountsAsZero() {
    BankWipeOffsets offsets = BankWipeOffsets.of(Map.of(CLAMPED, amount("40")), Map.of());

    assertThat(offsets.offsets()).containsExactlyEntriesOf(Map.of(CLAMPED, BigDecimal.ZERO));
    assertThat(offsets.clamped()).isEqualTo(1);
  }

  private static BigDecimal amount(String value) {
    return new BigDecimal(value);
  }
}

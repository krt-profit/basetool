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

package de.greluc.krt.profit.basetool.backend.patchchange.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.bank.api.BankConflictException;
import de.greluc.krt.profit.basetool.backend.bank.api.BankCurrencyWipe;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCurrencyWipe;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The currency wipe's effect (REQ-PATCH-040, -046): it runs only with its option, the orders before
 * the Bank, and reports one tile per count.
 */
@ExtendWith(MockitoExtension.class)
class CurrencyWipeEffectTest {

  private static final Instant LIVE_AT = Instant.parse("2026-11-14T20:00:00Z");
  private static final PatchChangePlan WIPING =
      new PatchChangePlan(LIVE_AT, new PatchChangeOptions(Set.of(), false, true, false));
  private static final PatchChangePlan KEEPING =
      new PatchChangePlan(LIVE_AT, new PatchChangeOptions(Set.of(), true, false, true));
  private static final PatchChangeRun RUN = new PatchChangeRun(UUID.randomUUID(), "4.4", WIPING);

  @Mock private JobOrderCurrencyWipe orders;
  @Mock private BankCurrencyWipe bank;

  private CurrencyWipeEffect effect;

  @BeforeEach
  void setUp() {
    effect = new CurrencyWipeEffect(orders, bank);
  }

  @Test
  void withoutItsOptionTheWipeTouchesNothing() {
    assertThat(effect.preview(KEEPING)).isEqualTo(PatchCounts.none());
    assertThat(effect.apply(new PatchChangeRun(UUID.randomUUID(), "4.4", KEEPING)))
        .isEqualTo(PatchCounts.none());
    verifyNoInteractions(orders, bank);
  }

  @Test
  void theOrdersRunBeforeTheBankAndEveryCountIsReported() {
    when(orders.apply(LIVE_AT, RUN.patchChangeId(), "4.4"))
        .thenReturn(new JobOrderCurrencyWipe.Effect(3, new BigDecimal("7000000"), 2, 1));
    when(bank.apply(LIVE_AT, RUN.patchChangeId()))
        .thenReturn(new BankCurrencyWipe.Effect(4, new BigDecimal("1250000.0000"), 2));

    PatchCounts counts = effect.apply(RUN);

    InOrder order = inOrder(orders, bank);
    order.verify(orders).apply(LIVE_AT, RUN.patchChangeId(), "4.4");
    order.verify(bank).apply(LIVE_AT, RUN.patchChangeId());
    assertThat(counts.asMap())
        .containsExactly(
            Map.entry(PatchCount.BANK_RESET_TOTAL, 1_250_000L),
            Map.entry(PatchCount.BANK_ACCOUNTS_CLAMPED, 2L),
            Map.entry(PatchCount.BANK_REQUESTS_CANCELED, 4L),
            Map.entry(PatchCount.AUEC_SHORTAGES, 3L),
            Map.entry(PatchCount.AUEC_SHORTAGE_TOTAL, 7_000_000L),
            Map.entry(PatchCount.AUEC_BANK_ATTEMPTS_CANCELED, 2L),
            Map.entry(PatchCount.AUEC_COMMITMENTS_ENDED, 1L));
  }

  @Test
  void aBankRefusalPassesThrough() {
    when(orders.apply(LIVE_AT, RUN.patchChangeId(), "4.4"))
        .thenReturn(new JobOrderCurrencyWipe.Effect(0, BigDecimal.ZERO, 0, 0));
    BankConflictException closed =
        new BankConflictException(
            BankConflictException.CODE_BANK_REQUEST_NOT_PENDING, "No longer pending");
    when(bank.apply(LIVE_AT, RUN.patchChangeId())).thenThrow(closed);

    assertThatThrownBy(() -> effect.apply(RUN)).isSameAs(closed);
  }
}

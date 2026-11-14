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

import de.greluc.krt.profit.basetool.backend.bank.api.BankCurrencyWipe;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderCurrencyWipe;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * The currency wipe of a patch change (REQ-PATCH-040): when its option is on, the aUEC orders and
 * then the Kartellbank follow the game's wiped money as of the live time (ADR-0384).
 *
 * <p>The orders run first: they take their guards before any Bank request, as a linked Bank
 * decision does, and release the bindings of the attempts they cancel before the shortages are
 * measured.
 */
@Component
@RequiredArgsConstructor
class CurrencyWipeEffect implements PatchEffect {

  private final JobOrderCurrencyWipe orders;
  private final BankCurrencyWipe bank;

  @Override
  public @NotNull PatchCounts preview(@NotNull PatchChangePlan plan) {
    if (!plan.options().currencyWipe()) {
      return PatchCounts.none();
    }
    return counts(orders.preview(plan.liveAt()), bank.preview(plan.liveAt()));
  }

  @Override
  public @NotNull PatchCounts apply(@NotNull PatchChangeRun run) {
    if (!run.plan().options().currencyWipe()) {
      return PatchCounts.none();
    }
    JobOrderCurrencyWipe.Effect wiped =
        orders.apply(run.plan().liveAt(), run.patchChangeId(), run.label());
    return counts(wiped, bank.apply(run.plan().liveAt(), run.patchChangeId()));
  }

  /**
   * Reports one tile per count the wipe has.
   *
   * @param orders what the wipe does in the aUEC orders
   * @param bank what it does in the Kartellbank
   * @return the counts
   */
  private static @NotNull PatchCounts counts(
      @NotNull JobOrderCurrencyWipe.Effect orders, @NotNull BankCurrencyWipe.Effect bank) {
    return PatchCounts.of(PatchCount.BANK_RESET_TOTAL, bank.resetTotal().longValueExact())
        .plus(PatchCounts.of(PatchCount.BANK_ACCOUNTS_CLAMPED, bank.accountsClamped()))
        .plus(PatchCounts.of(PatchCount.BANK_REQUESTS_CANCELED, bank.requestsCancelled()))
        .plus(PatchCounts.of(PatchCount.AUEC_SHORTAGES, orders.shortages()))
        .plus(
            PatchCounts.of(PatchCount.AUEC_SHORTAGE_TOTAL, orders.shortageTotal().longValueExact()))
        .plus(PatchCounts.of(PatchCount.AUEC_BANK_ATTEMPTS_CANCELED, orders.attemptsCancelled()))
        .plus(PatchCounts.of(PatchCount.AUEC_COMMITMENTS_ENDED, orders.commitmentsEnded()));
  }
}

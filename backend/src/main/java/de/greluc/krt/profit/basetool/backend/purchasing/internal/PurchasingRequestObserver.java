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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import de.greluc.krt.profit.basetool.backend.bank.api.BankPurposeKind;
import de.greluc.krt.profit.basetool.backend.bank.api.BookingRequestPurposeObserver;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheState;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hears of the end of every purpose-bound booking request of a deal and mirrors it onto the tranche
 * or the settlement it belongs to, inside the bank's transaction (REQ-PUR-014). The bank lets it
 * lock the deal before the request, so every path locks the deal first (REQ-PUR-026).
 */
@Component
@RequiredArgsConstructor
class PurchasingRequestObserver implements BookingRequestPurposeObserver {

  private final PurchasingDealTrancheRepository trancheRepository;
  private final PurchasingDealSettlementRepository settlementRepository;
  private final PurchasingDealService dealService;
  private final PurchasingTrancheService trancheService;
  private final PurchasingSettlementService settlementService;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull BankPurposeKind kind() {
    return BankPurposeKind.PURCHASING_DEAL;
  }

  /**
   * Locks the deal the request pays for, as a cancellation of the deal does before it cancels its
   * requests (REQ-PUR-026).
   *
   * @param purposeRef the deal
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void beforeDecision(@NotNull UUID purposeRef) {
    dealService.lock(purposeRef);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void onConfirmed(
      @NotNull UUID requestId, @NotNull UUID purposeRef, @NotNull UUID transactionId) {
    trancheRepository
        .findByBankRequestId(requestId)
        .ifPresentOrElse(
            tranche -> trancheService.onConfirmed(tranche, transactionId),
            () ->
                settlementRepository
                    .findByRemainderRequestId(requestId)
                    .ifPresent(settlementService::onRemainderConfirmed));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void onRejected(@NotNull UUID requestId, @NotNull UUID purposeRef) {
    notPaid(requestId, TrancheState.REFUSED, "REJECTED");
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void onCancelled(@NotNull UUID requestId, @NotNull UUID purposeRef) {
    notPaid(requestId, TrancheState.CANCELLED, "CANCELLED");
  }

  private void notPaid(@NotNull UUID requestId, @NotNull TrancheState state, @NotNull String code) {
    trancheRepository
        .findByBankRequestId(requestId)
        .ifPresentOrElse(
            tranche -> trancheService.onNotPaid(tranche, state),
            () ->
                settlementRepository
                    .findByRemainderRequestId(requestId)
                    .ifPresent(
                        settlement -> settlementService.onRemainderNotPaid(settlement, code)));
  }
}

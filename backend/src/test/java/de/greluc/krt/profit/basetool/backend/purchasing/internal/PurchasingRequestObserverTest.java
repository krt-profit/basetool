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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.bank.api.BankPurposeKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheState;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Dispatching the end of a purpose-bound request to its tranche or settlement (REQ-PUR-014). */
@ExtendWith(MockitoExtension.class)
class PurchasingRequestObserverTest {

  private static final UUID REQUEST = UUID.randomUUID();
  private static final UUID DEAL = UUID.randomUUID();
  private static final UUID TRANSACTION = UUID.randomUUID();

  @Mock private PurchasingDealTrancheRepository trancheRepository;
  @Mock private PurchasingDealSettlementRepository settlementRepository;
  @Mock private PurchasingTrancheService trancheService;
  @Mock private PurchasingSettlementService settlementService;

  private PurchasingRequestObserver observer;
  private PurchasingDealTranche tranche;
  private PurchasingDealSettlement settlement;

  @BeforeEach
  void setUp() {
    observer =
        new PurchasingRequestObserver(
            trancheRepository, settlementRepository, trancheService, settlementService);
    tranche = new PurchasingDealTranche();
    tranche.setId(UUID.randomUUID());
    settlement = new PurchasingDealSettlement();
    settlement.setId(UUID.randomUUID());
  }

  @Test
  void theObserverOwnsThePurchasingDealKind() {
    assertThat(observer.kind()).isEqualTo(BankPurposeKind.PURCHASING_DEAL);
  }

  @Test
  void aConfirmedRequestOfATrancheMarksTheTrancheConfirmed() {
    when(trancheRepository.findByBankRequestId(REQUEST)).thenReturn(Optional.of(tranche));

    observer.onConfirmed(REQUEST, DEAL, TRANSACTION);

    verify(trancheService).onConfirmed(tranche, TRANSACTION);
    verifyNoInteractions(settlementRepository, settlementService);
  }

  @Test
  void aConfirmedRequestOfASettlementSettlesTheDeal() {
    when(trancheRepository.findByBankRequestId(REQUEST)).thenReturn(Optional.empty());
    when(settlementRepository.findByRemainderRequestId(REQUEST))
        .thenReturn(Optional.of(settlement));

    observer.onConfirmed(REQUEST, DEAL, TRANSACTION);

    verify(settlementService).onRemainderConfirmed(settlement);
    verifyNoInteractions(trancheService);
  }

  @Test
  void aConfirmedRequestOfNeitherDoesNothing() {
    when(trancheRepository.findByBankRequestId(REQUEST)).thenReturn(Optional.empty());
    when(settlementRepository.findByRemainderRequestId(REQUEST)).thenReturn(Optional.empty());

    observer.onConfirmed(REQUEST, DEAL, TRANSACTION);

    verifyNoInteractions(trancheService, settlementService);
  }

  @Test
  void aRejectedRequestOfATrancheRefusesTheTranche() {
    when(trancheRepository.findByBankRequestId(REQUEST)).thenReturn(Optional.of(tranche));

    observer.onRejected(REQUEST, DEAL);

    verify(trancheService).onNotPaid(tranche, TrancheState.REFUSED);
    verifyNoInteractions(settlementRepository, settlementService);
  }

  @Test
  void aRejectedRequestOfASettlementReportsRejected() {
    when(trancheRepository.findByBankRequestId(REQUEST)).thenReturn(Optional.empty());
    when(settlementRepository.findByRemainderRequestId(REQUEST))
        .thenReturn(Optional.of(settlement));

    observer.onRejected(REQUEST, DEAL);

    verify(settlementService).onRemainderNotPaid(settlement, "REJECTED");
    verifyNoInteractions(trancheService);
  }

  @Test
  void aCancelledRequestOfATrancheCancelsTheTranche() {
    when(trancheRepository.findByBankRequestId(REQUEST)).thenReturn(Optional.of(tranche));

    observer.onCancelled(REQUEST, DEAL);

    verify(trancheService).onNotPaid(tranche, TrancheState.CANCELLED);
    verifyNoInteractions(settlementRepository, settlementService);
  }

  @Test
  void aCancelledRequestOfASettlementReportsCancelled() {
    when(trancheRepository.findByBankRequestId(REQUEST)).thenReturn(Optional.empty());
    when(settlementRepository.findByRemainderRequestId(REQUEST))
        .thenReturn(Optional.of(settlement));

    observer.onCancelled(REQUEST, DEAL);

    verify(settlementService).onRemainderNotPaid(settlement, "CANCELLED");
    verifyNoInteractions(trancheService);
  }

  @Test
  void aDecisionOnARequestOfNeitherDoesNothing() {
    when(trancheRepository.findByBankRequestId(REQUEST)).thenReturn(Optional.empty());
    when(settlementRepository.findByRemainderRequestId(REQUEST)).thenReturn(Optional.empty());

    observer.onRejected(REQUEST, DEAL);
    observer.onCancelled(REQUEST, DEAL);

    verifyNoInteractions(trancheService, settlementService);
  }
}

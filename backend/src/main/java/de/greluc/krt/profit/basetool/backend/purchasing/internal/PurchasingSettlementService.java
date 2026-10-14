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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurpose;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurposeKind;
import de.greluc.krt.profit.basetool.backend.bank.api.FundingAccounts;
import de.greluc.krt.profit.basetool.backend.bank.api.PurposeBoundRequests;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.LossReason;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemCode;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemException;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheState;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The settlement of a deal (REQ-PUR-017): the delegate accounts for the money received, the money
 * paid and any loss; a positive remainder returns to the funding account as a purpose-bound deposit
 * request, and the deal becomes {@code SETTLED} when that deposit is confirmed, or at once when
 * nothing is left to return.
 */
@Service
@RequiredArgsConstructor
public class PurchasingSettlementService {

  private final PurchasingDealRepository dealRepository;
  private final PurchasingDealTrancheRepository trancheRepository;
  private final PurchasingDealSettlementRepository settlementRepository;
  private final PurchasingSettingsService settingsService;
  private final PurchasingDealService dealService;
  private final PurchasingDealAssembler assembler;
  private final PurposeBoundRequests purposeBoundRequests;
  private final FundingAccounts fundingAccounts;
  private final AuthHelperService authHelperService;
  private final AuditRecorder auditRecorder;
  private final MeterRegistry meterRegistry;

  /**
   * Records or replaces the settlement of a deal whose delivery is closed.
   *
   * @param dealId the deal
   * @param request the money paid, the loss and the echoed settlement version
   * @return the deal with its settlement
   * @throws PurchasingProblemException with {@code PURCHASING_SETTLEMENT_INVALID} when the figures
   *     contradict each other or the deal's state
   */
  @NotNull
  @Transactional
  public DealDto settle(@NotNull UUID dealId, @NotNull SettleDealRequest request) {
    settingsService.requireEnabled();
    PurchasingDeal deal = dealService.lock(dealId);
    dealService.requireOwnDeal(deal);
    if (deal.getStatus() != DealStatus.DELIVERED && deal.getStatus() != DealStatus.FAILED) {
      throw PurchasingDealService.dealState("The deal is not waiting for its settlement");
    }
    List<PurchasingDealTranche> tranches =
        trancheRepository.findByDealIdOrderByCreatedAtAsc(dealId);
    if (tranches.stream().anyMatch(t -> t.getState() == TrancheState.REQUESTED)) {
      throw PurchasingDealService.dealState("A tranche is still waiting for the bank");
    }
    PurchasingDealSettlement settlement =
        settlementRepository.findByDealId(dealId).orElseGet(PurchasingDealSettlement::new);
    if (settlement.getId() != null) {
      OptimisticLock.checkRequired(
          settlement.getVersion(),
          request.version() == null ? -1L : request.version(),
          PurchasingDealSettlement.class,
          settlement.getId());
      if (settlement.getRemainderRequestId() != null) {
        purposeBoundRequests.cancel(
            settlement.getRemainderRequestId(), BankPurposeKind.PURCHASING_DEAL);
        settlement.setRemainderRequestId(null);
      }
    }
    long received =
        tranches.stream()
            .filter(t -> t.getState() == TrancheState.CONFIRMED)
            .mapToLong(PurchasingDealTranche::getAmount)
            .sum();
    long paid = request.moneyPaid();
    long loss = request.lossAmount();
    LossReason reason = loss > 0 ? request.lossReason() : null;
    boolean invalid =
        paid > received
            || loss > paid
            || (loss > 0 && reason == null)
            || (deal.getStatus() == DealStatus.FAILED && loss != paid);
    if (invalid) {
      throw new PurchasingProblemException(
          PurchasingProblemCode.PURCHASING_SETTLEMENT_INVALID,
          "The settlement figures contradict each other or the deal");
    }
    long remainder = received - paid;
    settlement.setDealId(dealId);
    settlement.setMoneyReceived(received);
    settlement.setMoneyPaid(paid);
    settlement.setRemainder(remainder);
    settlement.setLossAmount(loss);
    settlement.setLossReason(reason);
    settlement.setRecordedBy(authHelperService.currentUserId().orElse(null));
    settlement.setSettledAt(null);
    if (remainder > 0) {
      UUID account = settingsService.current().getFundingAccountId();
      if (account == null || fundingAccounts.findActive(account).isEmpty()) {
        throw new PurchasingProblemException(
            PurchasingProblemCode.PURCHASING_FUNDING_ACCOUNT_MISSING,
            "No active funding account is set for purchasing");
      }
      settlement.setRemainderRequestId(
          purposeBoundRequests.requestDeposit(
              account,
              BigDecimal.valueOf(remainder),
              new BankPurpose(BankPurposeKind.PURCHASING_DEAL, dealId)));
    } else {
      settlement.setRemainderRequestId(null);
      settlement.setSettledAt(Instant.now());
    }
    PurchasingDealSettlement saved = settlementRepository.saveAndFlush(settlement);
    auditRecorder.record(
        AuditEventType.DEAL_SETTLEMENT_RECORDED,
        dealId,
        PurchasingDealService.label(deal),
        null,
        PurchasingDealService.details(deal)
            .with("received", received)
            .with("paid", paid)
            .with("remainder", remainder)
            .with("loss", loss)
            .with("lossReason", reason == null ? null : reason.name()));
    if (saved.getSettledAt() != null) {
      markSettled(deal, saved);
    }
    return assembler.toDto(deal);
  }

  /**
   * The deposit request that returns a settlement's remainder was confirmed (REQ-PUR-014).
   *
   * @param settlement the settlement the request belongs to
   */
  @Transactional(propagation = Propagation.MANDATORY)
  void onRemainderConfirmed(@NotNull PurchasingDealSettlement settlement) {
    PurchasingDeal deal = dealService.lock(settlement.getDealId());
    settlement.setSettledAt(Instant.now());
    settlementRepository.saveAndFlush(settlement);
    markSettled(deal, settlement);
  }

  /**
   * The deposit request that returns a settlement's remainder was rejected or cancelled; the
   * delegate records the settlement again.
   *
   * @param settlement the settlement the request belongs to
   * @param outcome {@code REJECTED} or {@code CANCELLED}
   */
  @Transactional(propagation = Propagation.MANDATORY)
  void onRemainderNotPaid(@NotNull PurchasingDealSettlement settlement, @NotNull String outcome) {
    PurchasingDeal deal = dealService.lock(settlement.getDealId());
    settlement.setRemainderRequestId(null);
    settlementRepository.saveAndFlush(settlement);
    auditRecorder.record(
        AuditEventType.DEAL_SETTLEMENT_RECORDED,
        deal.getId(),
        PurchasingDealService.label(deal),
        null,
        PurchasingDealService.details(deal)
            .with("remainder", settlement.getRemainder())
            .with("remainderRequest", outcome));
  }

  private void markSettled(@NotNull PurchasingDeal deal, @NotNull PurchasingDealSettlement saved) {
    deal.setStatus(DealStatus.SETTLED);
    dealRepository.saveAndFlush(deal);
    auditRecorder.record(
        AuditEventType.DEAL_SETTLED,
        deal.getId(),
        PurchasingDealService.label(deal),
        null,
        PurchasingDealService.details(deal)
            .with("paid", saved.getMoneyPaid())
            .with("loss", saved.getLossAmount()));
    meterRegistry
        .counter(MetricNames.PURCHASING_DEALS, MetricNames.TAG_EVENT, "settled")
        .increment();
  }
}

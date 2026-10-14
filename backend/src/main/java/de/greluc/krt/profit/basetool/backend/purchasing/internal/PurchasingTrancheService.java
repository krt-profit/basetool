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
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PaymentModel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemCode;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemException;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheState;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The money of a deal (REQ-PUR-012 … REQ-PUR-014): requests a tranche as a purpose-bound bank
 * withdrawal to the delegate, enforces the class, the personal limit and the budget cap, and
 * mirrors the bank's decision onto the tranche and the deal in the bank's transaction.
 */
@Service
@RequiredArgsConstructor
public class PurchasingTrancheService {

  private static final Set<DealStatus> OPEN_FOR_MONEY =
      Set.of(
          DealStatus.DRAFT,
          DealStatus.SUBMITTED,
          DealStatus.FUNDED,
          DealStatus.IN_DELIVERY,
          DealStatus.DELIVERED);

  private final PurchasingDealRepository dealRepository;
  private final PurchasingDealTrancheRepository trancheRepository;
  private final PurchasingDelegateRepository delegateRepository;
  private final PurchasingSettingsService settingsService;
  private final PurchasingPauseService pauseService;
  private final PurchasingAccessPolicy accessPolicy;
  private final PurchasingDealService dealService;
  private final PurchasingDealAssembler assembler;
  private final PurposeBoundRequests purposeBoundRequests;
  private final FundingAccounts fundingAccounts;
  private final MessageSource messageSource;
  private final AuditRecorder auditRecorder;
  private final MeterRegistry meterRegistry;

  /**
   * Requests one tranche of a deal from the bank; the first one submits the deal (REQ-PUR-012).
   *
   * @param dealId the deal
   * @param request kind and amount of the tranche
   * @return the deal with the new tranche
   * @throws PurchasingProblemException when a rule of the deal, the class, the limit or the budget
   *     forbids the tranche
   */
  @NotNull
  @Transactional
  public DealDto request(@NotNull UUID dealId, @NotNull RequestTrancheRequest request) {
    settingsService.requireEnabled();
    final PurchasingDeal deal = dealService.lock(dealId);
    dealService.requireOwnDeal(deal);
    if (!accessPolicy.isActiveDelegate()) {
      throw new AccessDeniedException("Only an active trade delegate may request money");
    }
    if (!OPEN_FOR_MONEY.contains(deal.getStatus())) {
      throw PurchasingDealService.dealState("No money can be requested for this deal any more");
    }
    pauseService.requireNotPaused(deal.getRaisingOrgUnitId());
    if (deal.getStatus() == DealStatus.DRAFT || deal.getStatus() == DealStatus.SUBMITTED) {
      dealService.requireSubmittable(deal);
    } else {
      dealService.requireExternalCounterparty(deal);
    }
    requireKindFits(deal, request.kind());
    List<PurchasingDealTranche> tranches =
        trancheRepository.findByDealIdOrderByCreatedAtAsc(deal.getId());
    long live =
        tranches.stream().filter(PurchasingDealTranche::isLive).mapToLong(this::amountOf).sum();
    if (live + request.amount() > deal.getTotal()) {
      throw problem(
          PurchasingProblemCode.PURCHASING_TRANCHE_EXCEEDS_TOTAL,
          "The tranches would exceed the deal's total");
    }
    if (request.kind() == TrancheKind.ADVANCE) {
      requireAdvanceShare(deal, tranches, request.amount());
    }
    requireLimits(request.amount());
    UUID account = requireFundingAccount();
    UUID me = deal.getDelegateUserId();
    UUID bankRequestId =
        purposeBoundRequests.requestWithdrawal(
            account,
            BigDecimal.valueOf(request.amount()),
            me,
            new BankPurpose(BankPurposeKind.PURCHASING_DEAL, deal.getId()),
            justification(deal, request.kind()));
    PurchasingDealTranche tranche = new PurchasingDealTranche();
    tranche.setDealId(deal.getId());
    tranche.setKind(request.kind());
    tranche.setAmount(request.amount());
    tranche.setBankRequestId(bankRequestId);
    tranche.setState(TrancheState.REQUESTED);
    PurchasingDealTranche saved = trancheRepository.saveAndFlush(tranche);
    if (deal.getStatus() == DealStatus.DRAFT) {
      deal.setStatus(DealStatus.SUBMITTED);
      dealRepository.saveAndFlush(deal);
    }
    auditRecorder.record(
        AuditEventType.DEAL_TRANCHE_REQUESTED,
        deal.getId(),
        PurchasingDealService.label(deal),
        null,
        PurchasingDealService.details(deal)
            .with("tranche", saved.getId())
            .with("kind", saved.getKind().name())
            .with("trancheAmount", saved.getAmount())
            .with("bankRequest", bankRequestId));
    count(saved.getKind(), "requested");
    return assembler.toDto(deal);
  }

  /**
   * A purpose-bound request of a tranche was confirmed and booked (REQ-PUR-014).
   *
   * @param tranche the tranche its request pays
   * @param transactionId the ledger transaction the bank booked
   */
  @Transactional(propagation = Propagation.MANDATORY)
  void onConfirmed(@NotNull PurchasingDealTranche tranche, @NotNull UUID transactionId) {
    final PurchasingDeal deal = dealService.lock(tranche.getDealId());
    tranche.setState(TrancheState.CONFIRMED);
    tranche.setTransactionId(transactionId);
    tranche.setDecidedAt(Instant.now());
    trancheRepository.saveAndFlush(tranche);
    if (deal.getStatus() == DealStatus.SUBMITTED) {
      deal.setStatus(DealStatus.FUNDED);
      dealRepository.saveAndFlush(deal);
    }
    auditRecorder.record(
        AuditEventType.DEAL_TRANCHE_FUNDED,
        deal.getId(),
        PurchasingDealService.label(deal),
        null,
        PurchasingDealService.details(deal)
            .with("tranche", tranche.getId())
            .with("trancheAmount", tranche.getAmount()));
    count(tranche.getKind(), "confirmed");
  }

  /**
   * A purpose-bound request of a tranche was rejected or cancelled; no money moved (REQ-PUR-014).
   *
   * @param tranche the tranche its request pays
   * @param state {@code REFUSED} or {@code CANCELLED}
   */
  @Transactional(propagation = Propagation.MANDATORY)
  void onNotPaid(@NotNull PurchasingDealTranche tranche, @NotNull TrancheState state) {
    final PurchasingDeal deal = dealService.lock(tranche.getDealId());
    tranche.setState(state);
    tranche.setDecidedAt(Instant.now());
    trancheRepository.saveAndFlush(tranche);
    boolean anyLive =
        trancheRepository.findByDealIdOrderByCreatedAtAsc(deal.getId()).stream()
            .anyMatch(PurchasingDealTranche::isLive);
    if (deal.getStatus() == DealStatus.SUBMITTED && !anyLive) {
      deal.setStatus(DealStatus.DRAFT);
      dealRepository.saveAndFlush(deal);
    }
    auditRecorder.record(
        AuditEventType.DEAL_TRANCHE_REFUSED,
        deal.getId(),
        PurchasingDealService.label(deal),
        null,
        PurchasingDealService.details(deal)
            .with("tranche", tranche.getId())
            .with("trancheAmount", tranche.getAmount())
            .with("outcome", state.name()));
    count(tranche.getKind(), state == TrancheState.REFUSED ? "refused" : "cancelled");
  }

  private long amountOf(@NotNull PurchasingDealTranche tranche) {
    return tranche.getAmount();
  }

  private void requireKindFits(@NotNull PurchasingDeal deal, @NotNull TrancheKind kind) {
    PaymentModel model = deal.getPaymentModel();
    boolean fits =
        switch (kind) {
          case ADVANCE -> model == PaymentModel.ADVANCE || model == PaymentModel.PARTIAL;
          case ON_DELIVERY -> model == PaymentModel.ON_DELIVERY || model == PaymentModel.PARTIAL;
          case FINAL -> true;
        };
    if (!fits) {
      throw new BadRequestException("The tranche kind does not fit the deal's payment model");
    }
  }

  private void requireAdvanceShare(
      @NotNull PurchasingDeal deal, @NotNull List<PurchasingDealTranche> tranches, long amount) {
    PurchasingRiskClass rules =
        settingsService.classesInOrder().stream()
            .filter(row -> row.getCode() == deal.getRiskClass())
            .findFirst()
            .orElseThrow(() -> new BadRequestException("Unknown risk class"));
    long advanced =
        tranches.stream()
            .filter(PurchasingDealTranche::isLive)
            .filter(t -> t.getKind() == TrancheKind.ADVANCE)
            .mapToLong(PurchasingDealTranche::getAmount)
            .sum();
    long ceiling = deal.getTotal() * rules.getMaxAdvancePercent() / 100;
    if (advanced + amount > ceiling) {
      throw problem(
          PurchasingProblemCode.PURCHASING_ADVANCE_ABOVE_CLASS_SHARE,
          "The advance is above the share the risk class allows");
    }
  }

  private void requireLimits(long amount) {
    UUID me =
        accessPolicy.currentUserId().orElseThrow(() -> new AccessDeniedException("Not signed in"));
    PurchasingSetting setting = settingsService.current();
    Instant now = Instant.now();
    LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    Instant monthFrom = today.withDayOfMonth(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    Instant monthTo =
        today.withDayOfMonth(1).plusMonths(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    long limit =
        delegateRepository
            .findFirstByUserIdAndRevokedAtIsNull(me)
            .map(PurchasingDelegate::getMonthlyLimit)
            .orElse(setting.getDefaultMonthlyLimit());
    if (trancheRepository.sumLiveByDelegate(me, monthFrom, monthTo) + amount > limit) {
      throw problem(
          PurchasingProblemCode.PURCHASING_LIMIT_EXCEEDED,
          "The delegate's monthly limit would be exceeded");
    }
    if (setting.getBudgetCap() != null) {
      Instant from = periodStart(setting, today);
      Instant to = periodEnd(setting, today);
      if (trancheRepository.sumLive(from, to) + amount > setting.getBudgetCap()) {
        throw problem(
            PurchasingProblemCode.PURCHASING_BUDGET_EXCEEDED,
            "The budget cap of the period would be exceeded");
      }
    }
  }

  private static @NotNull LocalDate periodFirstDay(
      @NotNull PurchasingSetting setting, @NotNull LocalDate today) {
    return switch (setting.getBudgetPeriod()) {
      case MONTH -> today.withDayOfMonth(1);
      case QUARTER -> LocalDate.of(today.getYear(), (today.getMonthValue() - 1) / 3 * 3 + 1, 1);
      case YEAR -> LocalDate.of(today.getYear(), 1, 1);
    };
  }

  private static @NotNull Instant periodStart(
      @NotNull PurchasingSetting setting, @NotNull LocalDate today) {
    return periodFirstDay(setting, today).atStartOfDay().toInstant(ZoneOffset.UTC);
  }

  private static @NotNull Instant periodEnd(
      @NotNull PurchasingSetting setting, @NotNull LocalDate today) {
    LocalDate first = periodFirstDay(setting, today);
    LocalDate next =
        switch (setting.getBudgetPeriod()) {
          case MONTH -> first.plusMonths(1);
          case QUARTER -> first.plusMonths(3);
          case YEAR -> first.plusYears(1);
        };
    return next.atStartOfDay().toInstant(ZoneOffset.UTC);
  }

  private @NotNull UUID requireFundingAccount() {
    UUID account = settingsService.current().getFundingAccountId();
    if (account == null || fundingAccounts.findActive(account).isEmpty()) {
      throw problem(
          PurchasingProblemCode.PURCHASING_FUNDING_ACCOUNT_MISSING,
          "No active funding account is set for purchasing");
    }
    return account;
  }

  private @NotNull String justification(@NotNull PurchasingDeal deal, @NotNull TrancheKind kind) {
    String entry =
        deal.getMaterial() != null ? deal.getMaterial().getName() : deal.getGameItem().getName();
    return messageSource.getMessage(
        "purchasing.tranche.justification." + kind.name().toLowerCase(Locale.ROOT),
        new Object[] {String.valueOf(deal.getDealNo()), entry, amountText(deal.getAmount())},
        Locale.GERMAN);
  }

  /**
   * Formats an amount without trailing zeros for a generated text.
   *
   * @param amount the amount
   * @return the amount as plain text
   */
  @NotNull
  static String amountText(double amount) {
    return new BigDecimal(Double.toString(amount)).stripTrailingZeros().toPlainString();
  }

  private static @NotNull PurchasingProblemException problem(
      @NotNull PurchasingProblemCode code, @NotNull String detail) {
    return new PurchasingProblemException(code, detail);
  }

  private void count(@NotNull TrancheKind kind, @NotNull String outcome) {
    meterRegistry
        .counter(
            MetricNames.PURCHASING_TRANCHES,
            MetricNames.TAG_KIND,
            kind.name(),
            MetricNames.TAG_OUTCOME,
            outcome)
        .increment();
  }
}

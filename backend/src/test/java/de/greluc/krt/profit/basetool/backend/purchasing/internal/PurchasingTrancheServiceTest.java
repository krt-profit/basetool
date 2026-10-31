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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurpose;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurposeKind;
import de.greluc.krt.profit.basetool.backend.bank.api.FundingAccountRef;
import de.greluc.krt.profit.basetool.backend.bank.api.FundingAccounts;
import de.greluc.krt.profit.basetool.backend.bank.api.PurposeBoundRequests;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.purchasing.api.BudgetPeriod;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PaymentModel;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemCode;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemException;
import de.greluc.krt.profit.basetool.backend.purchasing.api.RiskClassCode;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.IsoFields;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.security.access.AccessDeniedException;

/**
 * Requesting tranches from the bank and mirroring the bank's decision (REQ-PUR-012, REQ-PUR-013,
 * REQ-PUR-014, REQ-PUR-019, REQ-PUR-026).
 */
@ExtendWith(MockitoExtension.class)
class PurchasingTrancheServiceTest {

  private static final UUID DEAL_ID = UUID.randomUUID();
  private static final UUID NEED_ID = UUID.randomUUID();
  private static final UUID UNIT = UUID.randomUUID();
  private static final UUID PARTNER = UUID.randomUUID();
  private static final UUID CALLER = UUID.randomUUID();
  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final UUID REQUEST_ID = UUID.randomUUID();
  private static final UUID TRANCHE_ID = UUID.randomUUID();
  private static final UUID TRANSACTION_ID = UUID.randomUUID();
  private static final String JUSTIFICATION = "Einkauf Deal 42";

  @Mock private PurchasingDealRepository dealRepository;
  @Mock private PurchasingDealTrancheRepository trancheRepository;
  @Mock private PurchasingDelegateRepository delegateRepository;
  @Mock private PurchasingSettingsService settingsService;
  @Mock private PurchasingPauseService pauseService;
  @Mock private PurchasingAccessPolicy accessPolicy;
  @Mock private PurchasingDealService dealService;
  @Mock private PurchasingDealAssembler assembler;
  @Mock private PurposeBoundRequests purposeBoundRequests;
  @Mock private FundingAccounts fundingAccounts;
  @Mock private MessageSource messageSource;
  @Mock private AuditRecorder auditRecorder;
  @Mock private DealDto dto;

  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  private PurchasingTrancheService service;
  private PurchasingDeal deal;
  private PurchasingSetting setting;
  private PurchasingRiskClass classB;

  @BeforeEach
  void setUp() {
    service =
        new PurchasingTrancheService(
            dealRepository,
            trancheRepository,
            delegateRepository,
            settingsService,
            pauseService,
            accessPolicy,
            dealService,
            assembler,
            purposeBoundRequests,
            fundingAccounts,
            messageSource,
            auditRecorder,
            meterRegistry);
    Material material = new Material();
    material.setName("Quantainium");
    deal = new PurchasingDeal();
    deal.setId(DEAL_ID);
    deal.setDealNo(42L);
    deal.setNeedId(NEED_ID);
    deal.setRaisingOrgUnitId(UNIT);
    deal.setPartnerId(PARTNER);
    deal.setDelegateUserId(CALLER);
    deal.setMaterial(material);
    deal.setAmount(10.0);
    deal.setUnitPrice(1000);
    deal.setTotal(10_000);
    deal.setPaymentModel(PaymentModel.PARTIAL);
    deal.setRiskClass(RiskClassCode.B);
    deal.setStatus(DealStatus.DRAFT);
    setting = new PurchasingSetting();
    setting.setFundingAccountId(ACCOUNT);
    setting.setDefaultMonthlyLimit(100_000);
    classB = new PurchasingRiskClass();
    classB.setCode(RiskClassCode.B);
    classB.setMaxTotal(50_000);
    classB.setMaxAdvancePercent(30);
  }

  @Test
  void requestingTheFirstTrancheRaisesTheWithdrawalAndSubmitsTheDraft() {
    stubDealAndDelegate();
    stubTranches();
    stubClasses();
    stubWithinLimits();
    stubBankSide(TrancheKind.ADVANCE, 2000);

    DealDto result =
        service.request(DEAL_ID, new RequestTrancheRequest(TrancheKind.ADVANCE, 2000L));

    assertThat(result).isSameAs(dto);
    assertThat(deal.getStatus()).isEqualTo(DealStatus.SUBMITTED);
    verify(dealRepository).saveAndFlush(deal);
    verify(dealService).requireSubmittable(deal);
    verify(dealService, never()).requireExternalCounterparty(any());
    verify(pauseService).requireNotPaused(UNIT);
    verify(trancheRepository, never()).sumLive(any(Instant.class), any(Instant.class));
    PurchasingDealTranche saved = savedTranche();
    assertThat(saved.getDealId()).isEqualTo(DEAL_ID);
    assertThat(saved.getKind()).isEqualTo(TrancheKind.ADVANCE);
    assertThat(saved.getAmount()).isEqualTo(2000L);
    assertThat(saved.getBankRequestId()).isEqualTo(REQUEST_ID);
    assertThat(saved.getState()).isEqualTo(TrancheState.REQUESTED);
    assertThat(counter(TrancheKind.ADVANCE, "requested")).isEqualTo(1.0);
  }

  @Test
  void theJustificationIsBuiltFromTheDealNumberTheEntryAndTheAmount() {
    stubDealAndDelegate();
    stubTranches();
    stubWithinLimits();
    stubBankSide(TrancheKind.FINAL, 4000);

    service.request(DEAL_ID, new RequestTrancheRequest(TrancheKind.FINAL, 4000L));

    ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
    verify(messageSource)
        .getMessage(
            eq("purchasing.tranche.justification.final"), arguments.capture(), eq(Locale.GERMAN));
    assertThat(arguments.getValue()).containsExactly("42", "Quantainium", "10");
  }

  @Test
  void theAuditEventCarriesIdsAndAmountsButNoPartnerName() {
    stubDealAndDelegate();
    stubTranches();
    stubWithinLimits();
    stubBankSide(TrancheKind.FINAL, 4000);

    service.request(DEAL_ID, new RequestTrancheRequest(TrancheKind.FINAL, 4000L));

    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.DEAL_TRANCHE_REQUESTED),
            eq(DEAL_ID),
            eq("Deal 42"),
            isNull(),
            details.capture());
    String text = details.getValue().toString();
    assertThat(text)
        .contains("partner=" + PARTNER)
        .contains("kind=FINAL")
        .contains("trancheAmount=4000")
        .contains("tranche=" + TRANCHE_ID)
        .contains("bankRequest=" + REQUEST_ID)
        .doesNotContain("name");
  }

  @Test
  void aTrancheOnAFundedDealChecksOnlyTheCounterpartyAndLeavesTheStatus() {
    deal.setStatus(DealStatus.FUNDED);
    stubDealAndDelegate();
    stubTranches();
    stubWithinLimits();
    stubBankSide(TrancheKind.ON_DELIVERY, 1000);

    service.request(DEAL_ID, new RequestTrancheRequest(TrancheKind.ON_DELIVERY, 1000L));

    assertThat(deal.getStatus()).isEqualTo(DealStatus.FUNDED);
    verify(dealService).requireExternalCounterparty(deal);
    verify(dealService, never()).requireSubmittable(any());
    verify(dealRepository, never()).saveAndFlush(any());
    assertThat(counter(TrancheKind.ON_DELIVERY, "requested")).isEqualTo(1.0);
  }

  @Test
  void refusedAndCancelledTranchesDoNotCountAgainstTheTotal() {
    stubDealAndDelegate();
    stubTranches(
        tranche(TrancheKind.ADVANCE, TrancheState.REFUSED, 9000),
        tranche(TrancheKind.FINAL, TrancheState.CANCELLED, 9000));
    stubWithinLimits();
    stubBankSide(TrancheKind.FINAL, 10_000);

    DealDto result =
        service.request(DEAL_ID, new RequestTrancheRequest(TrancheKind.FINAL, 10_000L));

    assertThat(result).isSameAs(dto);
  }

  @Test
  void anAdvanceExactlyAtTheClassShareIsAllowed() {
    stubDealAndDelegate();
    stubTranches(tranche(TrancheKind.ADVANCE, TrancheState.CONFIRMED, 1000));
    stubClasses();
    stubWithinLimits();
    stubBankSide(TrancheKind.ADVANCE, 2000);

    DealDto result =
        service.request(DEAL_ID, new RequestTrancheRequest(TrancheKind.ADVANCE, 2000L));

    assertThat(result).isSameAs(dto);
  }

  @Test
  void aCallerWhoIsNotTheDealsDelegateIsRefused() {
    when(dealService.lock(DEAL_ID)).thenReturn(deal);
    doThrow(new AccessDeniedException("not the delegate")).when(dealService).requireOwnDeal(deal);

    assertThatThrownBy(
            () -> service.request(DEAL_ID, new RequestTrancheRequest(TrancheKind.FINAL, 100L)))
        .isInstanceOf(AccessDeniedException.class);

    verifyNothingRequested();
  }

  @Test
  void aCallerWhoIsNoLongerAnActiveDelegateIsRefused() {
    when(dealService.lock(DEAL_ID)).thenReturn(deal);
    when(accessPolicy.isActiveDelegate()).thenReturn(false);

    assertThatThrownBy(
            () -> service.request(DEAL_ID, new RequestTrancheRequest(TrancheKind.FINAL, 100L)))
        .isInstanceOf(AccessDeniedException.class);

    verifyNothingRequested();
  }

  @ParameterizedTest
  @EnumSource(
      value = DealStatus.class,
      names = {"SETTLED", "CANCELLED", "FAILED"})
  void aDealThatIsClosedForMoneyTakesNoTranche(DealStatus status) {
    deal.setStatus(status);
    stubDealAndDelegate();

    assertRefused(
        () -> service.request(DEAL_ID, new RequestTrancheRequest(TrancheKind.FINAL, 100L)),
        PurchasingProblemCode.PURCHASING_DEAL_STATE);

    verifyNothingRequested();
  }

  @Test
  void aPausedPurchasingTakesNoTranche() {
    stubDealAndDelegate();
    doThrow(
            new PurchasingProblemException(
                PurchasingProblemCode.PURCHASING_PAUSED, "Purchasing is paused"))
        .when(pauseService)
        .requireNotPaused(UNIT);

    assertRefused(
        () -> service.request(DEAL_ID, new RequestTrancheRequest(TrancheKind.FINAL, 100L)),
        PurchasingProblemCode.PURCHASING_PAUSED);

    verifyNothingRequested();
  }

  @ParameterizedTest
  @CsvSource({"ON_DELIVERY,ADVANCE", "ADVANCE,ON_DELIVERY"})
  void aTrancheKindThatDoesNotFitThePaymentModelIsRefused(PaymentModel model, TrancheKind kind) {
    deal.setPaymentModel(model);
    stubDealAndDelegate();

    assertThatThrownBy(() -> service.request(DEAL_ID, new RequestTrancheRequest(kind, 100L)))
        .isInstanceOf(BadRequestException.class);

    verifyNothingRequested();
  }

  @Test
  void tranchesAboveTheDealTotalAreRefused() {
    stubDealAndDelegate();
    stubTranches(tranche(TrancheKind.ON_DELIVERY, TrancheState.REQUESTED, 8000));

    assertRefused(
        () -> service.request(DEAL_ID, new RequestTrancheRequest(TrancheKind.FINAL, 2001L)),
        PurchasingProblemCode.PURCHASING_TRANCHE_EXCEEDS_TOTAL);

    verifyNothingRequested();
  }

  @Test
  void anAdvanceAboveTheShareOfTheRiskClassIsRefused() {
    stubDealAndDelegate();
    stubTranches(
        tranche(TrancheKind.ADVANCE, TrancheState.CONFIRMED, 2000),
        tranche(TrancheKind.ON_DELIVERY, TrancheState.CONFIRMED, 4000));
    stubClasses();

    assertRefused(
        () -> service.request(DEAL_ID, new RequestTrancheRequest(TrancheKind.ADVANCE, 1001L)),
        PurchasingProblemCode.PURCHASING_ADVANCE_ABOVE_CLASS_SHARE);

    verifyNothingRequested();
  }

  @Test
  void anAdvanceOfADealWhoseClassHasNoRowIsRefused() {
    deal.setRiskClass(RiskClassCode.C);
    stubDealAndDelegate();
    stubTranches();
    stubClasses();

    assertThatThrownBy(
            () -> service.request(DEAL_ID, new RequestTrancheRequest(TrancheKind.ADVANCE, 100L)))
        .isInstanceOf(BadRequestException.class);

    verifyNothingRequested();
  }

  @Test
  void aPersonalMonthlyLimitReplacesTheDefaultAndIsCheckedAgainstTheCurrentMonth() {
    PurchasingDelegate delegate = new PurchasingDelegate();
    delegate.setMonthlyLimit(4000L);
    stubDealAndDelegate();
    stubTranches();
    when(accessPolicy.currentUserId()).thenReturn(Optional.of(CALLER));
    when(settingsService.current()).thenReturn(setting);
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(CALLER))
        .thenReturn(Optional.of(delegate));
    when(trancheRepository.sumLiveByDelegate(eq(CALLER), any(Instant.class), any(Instant.class)))
        .thenReturn(2000L);

    assertRefused(
        () -> service.request(DEAL_ID, new RequestTrancheRequest(TrancheKind.FINAL, 2001L)),
        PurchasingProblemCode.PURCHASING_LIMIT_EXCEEDED);

    ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
    verify(trancheRepository).sumLiveByDelegate(eq(CALLER), from.capture(), to.capture());
    LocalDate first = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
    assertThat(from.getValue()).isEqualTo(first.atStartOfDay().toInstant(ZoneOffset.UTC));
    assertThat(to.getValue())
        .isEqualTo(first.plusMonths(1).atStartOfDay().toInstant(ZoneOffset.UTC));
    verifyNothingRequested();
  }

  @Test
  void theDefaultMonthlyLimitAppliesWhenTheDelegateHasNoPersonalOne() {
    setting.setDefaultMonthlyLimit(5000);
    PurchasingDelegate delegate = new PurchasingDelegate();
    stubDealAndDelegate();
    stubTranches();
    when(accessPolicy.currentUserId()).thenReturn(Optional.of(CALLER));
    when(settingsService.current()).thenReturn(setting);
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(CALLER))
        .thenReturn(Optional.of(delegate));
    when(trancheRepository.sumLiveByDelegate(eq(CALLER), any(Instant.class), any(Instant.class)))
        .thenReturn(3000L);

    assertRefused(
        () -> service.request(DEAL_ID, new RequestTrancheRequest(TrancheKind.FINAL, 2001L)),
        PurchasingProblemCode.PURCHASING_LIMIT_EXCEEDED);

    verifyNothingRequested();
  }

  @ParameterizedTest
  @EnumSource(BudgetPeriod.class)
  void theBudgetCapOfThePeriodIsEnforcedOverTheWholePeriod(BudgetPeriod period) {
    setting.setBudgetCap(10_000L);
    setting.setBudgetPeriod(period);
    stubDealAndDelegate();
    stubTranches();
    stubWithinLimits();
    when(trancheRepository.sumLive(any(Instant.class), any(Instant.class))).thenReturn(9000L);

    assertRefused(
        () -> service.request(DEAL_ID, new RequestTrancheRequest(TrancheKind.FINAL, 1001L)),
        PurchasingProblemCode.PURCHASING_BUDGET_EXCEEDED);

    ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
    ArgumentCaptor<Instant> to = ArgumentCaptor.forClass(Instant.class);
    verify(trancheRepository).sumLive(from.capture(), to.capture());
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate first =
        switch (period) {
          case MONTH -> today.withDayOfMonth(1);
          case QUARTER -> today.with(IsoFields.DAY_OF_QUARTER, 1);
          case YEAR -> today.withDayOfYear(1);
        };
    LocalDate next =
        switch (period) {
          case MONTH -> first.plusMonths(1);
          case QUARTER -> first.plusMonths(3);
          case YEAR -> first.plusYears(1);
        };
    assertThat(from.getValue()).isEqualTo(first.atStartOfDay().toInstant(ZoneOffset.UTC));
    assertThat(to.getValue()).isEqualTo(next.atStartOfDay().toInstant(ZoneOffset.UTC));
    verifyNothingRequested();
  }

  @Test
  void aTrancheThatFillsTheBudgetCapExactlyIsAllowed() {
    setting.setBudgetCap(10_000L);
    stubDealAndDelegate();
    stubTranches();
    stubWithinLimits();
    when(trancheRepository.sumLive(any(Instant.class), any(Instant.class))).thenReturn(9000L);
    stubBankSide(TrancheKind.FINAL, 1000);

    DealDto result = service.request(DEAL_ID, new RequestTrancheRequest(TrancheKind.FINAL, 1000L));

    assertThat(result).isSameAs(dto);
  }

  @Test
  void aMissingFundingAccountIsRefused() {
    setting.setFundingAccountId(null);
    stubDealAndDelegate();
    stubTranches();
    stubWithinLimits();

    assertRefused(
        () -> service.request(DEAL_ID, new RequestTrancheRequest(TrancheKind.FINAL, 100L)),
        PurchasingProblemCode.PURCHASING_FUNDING_ACCOUNT_MISSING);

    verifyNothingRequested();
  }

  @Test
  void anInactiveFundingAccountIsRefused() {
    stubDealAndDelegate();
    stubTranches();
    stubWithinLimits();
    when(fundingAccounts.findActive(ACCOUNT)).thenReturn(Optional.empty());

    assertRefused(
        () -> service.request(DEAL_ID, new RequestTrancheRequest(TrancheKind.FINAL, 100L)),
        PurchasingProblemCode.PURCHASING_FUNDING_ACCOUNT_MISSING);

    verifyNothingRequested();
  }

  @Test
  void aConfirmedTrancheIsBookedAndFundsASubmittedDeal() {
    deal.setStatus(DealStatus.SUBMITTED);
    PurchasingDealTranche tranche = tranche(TrancheKind.ADVANCE, TrancheState.REQUESTED, 2000);
    when(dealService.lock(DEAL_ID)).thenReturn(deal);

    service.onConfirmed(tranche, TRANSACTION_ID);

    assertThat(tranche.getState()).isEqualTo(TrancheState.CONFIRMED);
    assertThat(tranche.getTransactionId()).isEqualTo(TRANSACTION_ID);
    assertThat(tranche.getDecidedAt()).isNotNull();
    assertThat(deal.getStatus()).isEqualTo(DealStatus.FUNDED);
    verify(trancheRepository).saveAndFlush(tranche);
    verify(dealRepository).saveAndFlush(deal);
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.DEAL_TRANCHE_FUNDED),
            eq(DEAL_ID),
            eq("Deal 42"),
            isNull(),
            details.capture());
    assertThat(details.getValue().toString())
        .contains("tranche=" + tranche.getId())
        .contains("trancheAmount=2000");
    assertThat(counter(TrancheKind.ADVANCE, "confirmed")).isEqualTo(1.0);
  }

  @Test
  void aConfirmedTrancheOfAnAdvancedDealLeavesTheStatus() {
    deal.setStatus(DealStatus.IN_DELIVERY);
    PurchasingDealTranche tranche = tranche(TrancheKind.FINAL, TrancheState.REQUESTED, 500);
    when(dealService.lock(DEAL_ID)).thenReturn(deal);

    service.onConfirmed(tranche, TRANSACTION_ID);

    assertThat(deal.getStatus()).isEqualTo(DealStatus.IN_DELIVERY);
    verify(dealRepository, never()).saveAndFlush(any());
    assertThat(tranche.getState()).isEqualTo(TrancheState.CONFIRMED);
  }

  @Test
  void aRefusedTrancheSendsASubmittedDealWithoutLiveTrancheBackToDraft() {
    deal.setStatus(DealStatus.SUBMITTED);
    PurchasingDealTranche tranche = tranche(TrancheKind.ADVANCE, TrancheState.REQUESTED, 2000);
    when(dealService.lock(DEAL_ID)).thenReturn(deal);
    when(trancheRepository.findByDealIdOrderByCreatedAtAsc(DEAL_ID)).thenReturn(List.of(tranche));

    service.onNotPaid(tranche, TrancheState.REFUSED);

    assertThat(tranche.getState()).isEqualTo(TrancheState.REFUSED);
    assertThat(tranche.getDecidedAt()).isNotNull();
    assertThat(deal.getStatus()).isEqualTo(DealStatus.DRAFT);
    verify(trancheRepository).saveAndFlush(tranche);
    verify(dealRepository).saveAndFlush(deal);
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.DEAL_TRANCHE_REFUSED),
            eq(DEAL_ID),
            eq("Deal 42"),
            isNull(),
            details.capture());
    assertThat(details.getValue().toString()).contains("outcome=REFUSED");
    assertThat(counter(TrancheKind.ADVANCE, "refused")).isEqualTo(1.0);
  }

  @Test
  void aCancelledTrancheLeavesASubmittedDealSubmittedWhileAnotherTrancheIsLive() {
    deal.setStatus(DealStatus.SUBMITTED);
    PurchasingDealTranche cancelled = tranche(TrancheKind.ADVANCE, TrancheState.REQUESTED, 2000);
    PurchasingDealTranche other = tranche(TrancheKind.FINAL, TrancheState.REQUESTED, 3000);
    when(dealService.lock(DEAL_ID)).thenReturn(deal);
    when(trancheRepository.findByDealIdOrderByCreatedAtAsc(DEAL_ID))
        .thenReturn(List.of(cancelled, other));

    service.onNotPaid(cancelled, TrancheState.CANCELLED);

    assertThat(cancelled.getState()).isEqualTo(TrancheState.CANCELLED);
    assertThat(deal.getStatus()).isEqualTo(DealStatus.SUBMITTED);
    verify(dealRepository, never()).saveAndFlush(any());
    assertThat(counter(TrancheKind.ADVANCE, "cancelled")).isEqualTo(1.0);
  }

  @Test
  void aRefusedTrancheLeavesAFundedDealFunded() {
    deal.setStatus(DealStatus.FUNDED);
    PurchasingDealTranche tranche = tranche(TrancheKind.FINAL, TrancheState.REQUESTED, 500);
    when(dealService.lock(DEAL_ID)).thenReturn(deal);
    when(trancheRepository.findByDealIdOrderByCreatedAtAsc(DEAL_ID)).thenReturn(List.of(tranche));

    service.onNotPaid(tranche, TrancheState.REFUSED);

    assertThat(deal.getStatus()).isEqualTo(DealStatus.FUNDED);
    verify(dealRepository, never()).saveAndFlush(any());
  }

  @ParameterizedTest
  @CsvSource({"12.5,12.5", "3.0,3", "100.0,100", "0.0123,0.0123", "2500.50,2500.5"})
  void anAmountTextDropsTrailingZeros(double amount, String expected) {
    assertThat(PurchasingTrancheService.amountText(amount)).isEqualTo(expected);
  }

  private void stubDealAndDelegate() {
    when(dealService.lock(DEAL_ID)).thenReturn(deal);
    when(accessPolicy.isActiveDelegate()).thenReturn(true);
  }

  private void stubTranches(PurchasingDealTranche... existing) {
    when(trancheRepository.findByDealIdOrderByCreatedAtAsc(DEAL_ID)).thenReturn(List.of(existing));
  }

  private void stubClasses() {
    PurchasingRiskClass classA = new PurchasingRiskClass();
    classA.setCode(RiskClassCode.A);
    classA.setMaxTotal(5000);
    classA.setMaxAdvancePercent(50);
    when(settingsService.classesInOrder()).thenReturn(List.of(classA, classB));
  }

  private void stubWithinLimits() {
    when(accessPolicy.currentUserId()).thenReturn(Optional.of(CALLER));
    when(settingsService.current()).thenReturn(setting);
    when(delegateRepository.findFirstByUserIdAndRevokedAtIsNull(CALLER))
        .thenReturn(Optional.empty());
    when(trancheRepository.sumLiveByDelegate(eq(CALLER), any(Instant.class), any(Instant.class)))
        .thenReturn(0L);
  }

  private void stubBankSide(TrancheKind kind, long amount) {
    when(fundingAccounts.findActive(ACCOUNT))
        .thenReturn(Optional.of(new FundingAccountRef(ACCOUNT, "KRT-1", "Einkauf", "CARTEL")));
    when(messageSource.getMessage(
            eq("purchasing.tranche.justification." + kind.name().toLowerCase(Locale.ROOT)),
            any(Object[].class),
            eq(Locale.GERMAN)))
        .thenReturn(JUSTIFICATION);
    when(purposeBoundRequests.requestWithdrawal(
            ACCOUNT,
            BigDecimal.valueOf(amount),
            CALLER,
            new BankPurpose(BankPurposeKind.PURCHASING_DEAL, DEAL_ID),
            JUSTIFICATION))
        .thenReturn(REQUEST_ID);
    when(trancheRepository.saveAndFlush(any(PurchasingDealTranche.class)))
        .thenAnswer(invocation -> {
          PurchasingDealTranche saved = invocation.getArgument(0);
          saved.setId(TRANCHE_ID);
          return saved;
        });
    when(assembler.toDto(deal)).thenReturn(dto);
  }

  private PurchasingDealTranche savedTranche() {
    ArgumentCaptor<PurchasingDealTranche> captor =
        ArgumentCaptor.forClass(PurchasingDealTranche.class);
    verify(trancheRepository).saveAndFlush(captor.capture());
    return captor.getValue();
  }

  private void verifyNothingRequested() {
    verifyNoInteractions(purposeBoundRequests, auditRecorder);
    verify(trancheRepository, never()).saveAndFlush(any());
    verify(dealRepository, never()).saveAndFlush(any());
  }

  private double counter(TrancheKind kind, String outcome) {
    return meterRegistry
        .get(MetricNames.PURCHASING_TRANCHES)
        .tag(MetricNames.TAG_KIND, kind.name())
        .tag(MetricNames.TAG_OUTCOME, outcome)
        .counter()
        .count();
  }

  private static PurchasingDealTranche tranche(TrancheKind kind, TrancheState state, long amount) {
    PurchasingDealTranche tranche = new PurchasingDealTranche();
    tranche.setId(UUID.randomUUID());
    tranche.setDealId(DEAL_ID);
    tranche.setKind(kind);
    tranche.setState(state);
    tranche.setAmount(amount);
    return tranche;
  }

  private static void assertRefused(ThrowingCallable call, PurchasingProblemCode code) {
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            PurchasingProblemException.class, e -> assertThat(e.getProblemCode()).isEqualTo(code));
  }
}

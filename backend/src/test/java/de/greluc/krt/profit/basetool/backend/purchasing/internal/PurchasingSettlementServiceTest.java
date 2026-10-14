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
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurpose;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurposeKind;
import de.greluc.krt.profit.basetool.backend.bank.api.FundingAccountRef;
import de.greluc.krt.profit.basetool.backend.bank.api.FundingAccounts;
import de.greluc.krt.profit.basetool.backend.bank.api.PurposeBoundRequests;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.platform.api.MetricNames;
import de.greluc.krt.profit.basetool.backend.purchasing.api.DealStatus;
import de.greluc.krt.profit.basetool.backend.purchasing.api.LossReason;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemCode;
import de.greluc.krt.profit.basetool.backend.purchasing.api.PurchasingProblemException;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheKind;
import de.greluc.krt.profit.basetool.backend.purchasing.api.TrancheState;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Recording a settlement, returning the remainder through the bank and settling the deal
 * (REQ-PUR-017, REQ-PUR-014, REQ-PUR-026).
 */
@ExtendWith(MockitoExtension.class)
class PurchasingSettlementServiceTest {

  private static final UUID DEAL_ID = UUID.randomUUID();
  private static final UUID CALLER = UUID.randomUUID();
  private static final UUID ACCOUNT = UUID.randomUUID();
  private static final UUID SETTLEMENT_ID = UUID.randomUUID();
  private static final UUID OLD_REQUEST = UUID.randomUUID();
  private static final UUID NEW_REQUEST = UUID.randomUUID();

  @Mock private PurchasingDealRepository dealRepository;
  @Mock private PurchasingDealTrancheRepository trancheRepository;
  @Mock private PurchasingDealSettlementRepository settlementRepository;
  @Mock private PurchasingSettingsService settingsService;
  @Mock private PurchasingDealService dealService;
  @Mock private PurchasingDealAssembler assembler;
  @Mock private PurposeBoundRequests purposeBoundRequests;
  @Mock private FundingAccounts fundingAccounts;
  @Mock private AuthHelperService authHelperService;
  @Mock private AuditRecorder auditRecorder;
  @Mock private DealDto dto;

  private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

  private PurchasingSettlementService service;
  private PurchasingDeal deal;
  private PurchasingSetting setting;

  @BeforeEach
  void setUp() {
    service =
        new PurchasingSettlementService(
            dealRepository,
            trancheRepository,
            settlementRepository,
            settingsService,
            dealService,
            assembler,
            purposeBoundRequests,
            fundingAccounts,
            authHelperService,
            auditRecorder,
            meterRegistry);
    deal = new PurchasingDeal();
    deal.setId(DEAL_ID);
    deal.setDealNo(7L);
    deal.setPartnerId(UUID.randomUUID());
    deal.setStatus(DealStatus.DELIVERED);
    setting = new PurchasingSetting();
    setting.setFundingAccountId(ACCOUNT);
  }

  @Test
  void aPositiveRemainderRaisesTheDepositAndKeepsTheDealOpen() {
    stubDealWithReceived10000();
    stubNoSettlement();
    stubRecorder();
    stubFundingAccount();
    when(purposeBoundRequests.requestDeposit(
            ACCOUNT,
            BigDecimal.valueOf(3000L),
            new BankPurpose(BankPurposeKind.PURCHASING_DEAL, DEAL_ID)))
        .thenReturn(NEW_REQUEST);
    stubSaveAndDto();

    DealDto result = service.settle(DEAL_ID, new SettleDealRequest(7000L, 0L, null, null));

    assertThat(result).isSameAs(dto);
    PurchasingDealSettlement saved = savedSettlement();
    assertThat(saved.getDealId()).isEqualTo(DEAL_ID);
    assertThat(saved.getMoneyReceived()).isEqualTo(10_000L);
    assertThat(saved.getMoneyPaid()).isEqualTo(7000L);
    assertThat(saved.getRemainder()).isEqualTo(3000L);
    assertThat(saved.getLossAmount()).isZero();
    assertThat(saved.getLossReason()).isNull();
    assertThat(saved.getRemainderRequestId()).isEqualTo(NEW_REQUEST);
    assertThat(saved.getRecordedBy()).isEqualTo(CALLER);
    assertThat(saved.getSettledAt()).isNull();
    assertThat(deal.getStatus()).isEqualTo(DealStatus.DELIVERED);
    verify(dealRepository, never()).saveAndFlush(any());
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.DEAL_SETTLEMENT_RECORDED),
            eq(DEAL_ID),
            eq("Deal 7"),
            isNull(),
            details.capture());
    assertThat(details.getValue().toString())
        .contains("received=10000")
        .contains("paid=7000")
        .contains("remainder=3000")
        .contains("loss=0")
        .doesNotContain("name");
    assertThat(meterRegistry.find(MetricNames.PURCHASING_DEALS).counter()).isNull();
  }

  @Test
  void aZeroRemainderSettlesTheDealAtOnce() {
    stubDealWithReceived10000();
    stubNoSettlement();
    stubRecorder();
    stubSaveAndDto();

    DealDto result =
        service.settle(DEAL_ID, new SettleDealRequest(10_000L, 0L, LossReason.OTHER, null));

    assertThat(result).isSameAs(dto);
    PurchasingDealSettlement saved = savedSettlement();
    assertThat(saved.getRemainder()).isZero();
    assertThat(saved.getRemainderRequestId()).isNull();
    assertThat(saved.getSettledAt()).isNotNull();
    assertThat(saved.getLossReason()).isNull();
    assertThat(deal.getStatus()).isEqualTo(DealStatus.SETTLED);
    verify(dealRepository).saveAndFlush(deal);
    verify(purposeBoundRequests, never()).requestDeposit(any(), any(), any());
    verify(fundingAccounts, never()).findActive(any());
    ArgumentCaptor<AuditEventType> types = ArgumentCaptor.forClass(AuditEventType.class);
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder, times(2))
        .record(types.capture(), eq(DEAL_ID), eq("Deal 7"), isNull(), details.capture());
    assertThat(types.getAllValues())
        .containsExactly(AuditEventType.DEAL_SETTLEMENT_RECORDED, AuditEventType.DEAL_SETTLED);
    assertThat(details.getAllValues().get(1).toString())
        .contains("status=SETTLED")
        .contains("paid=10000")
        .contains("loss=0");
    assertThat(
            meterRegistry
                .get(MetricNames.PURCHASING_DEALS)
                .tag(MetricNames.TAG_EVENT, "settled")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void aFailedDealRecordsTheWholePaymentAsLossAndReturnsTheRest() {
    deal.setStatus(DealStatus.FAILED);
    stubDealWithReceived10000();
    stubNoSettlement();
    stubRecorder();
    stubFundingAccount();
    when(purposeBoundRequests.requestDeposit(
            ACCOUNT,
            BigDecimal.valueOf(6000L),
            new BankPurpose(BankPurposeKind.PURCHASING_DEAL, DEAL_ID)))
        .thenReturn(NEW_REQUEST);
    stubSaveAndDto();

    service.settle(DEAL_ID, new SettleDealRequest(4000L, 4000L, LossReason.FRAUD, null));

    PurchasingDealSettlement saved = savedSettlement();
    assertThat(saved.getLossAmount()).isEqualTo(4000L);
    assertThat(saved.getLossReason()).isEqualTo(LossReason.FRAUD);
    assertThat(saved.getRemainderRequestId()).isEqualTo(NEW_REQUEST);
    assertThat(deal.getStatus()).isEqualTo(DealStatus.FAILED);
  }

  @Test
  void aFailedDealWhoseLossDiffersFromThePaymentIsRefused() {
    deal.setStatus(DealStatus.FAILED);
    stubDealWithReceived10000();
    stubNoSettlement();

    assertInvalid(new SettleDealRequest(4000L, 3000L, LossReason.FRAUD, null));
  }

  @Test
  void aPaymentAboveTheMoneyReceivedIsRefused() {
    stubDealWithReceived10000();
    stubNoSettlement();

    assertInvalid(new SettleDealRequest(10_001L, 0L, null, null));
  }

  @Test
  void aLossAboveThePaymentIsRefused() {
    stubDealWithReceived10000();
    stubNoSettlement();

    assertInvalid(new SettleDealRequest(5000L, 5001L, LossReason.QUALITY, null));
  }

  @Test
  void aLossWithoutAReasonIsRefused() {
    stubDealWithReceived10000();
    stubNoSettlement();

    assertInvalid(new SettleDealRequest(5000L, 100L, null, null));
  }

  @Test
  void aTrancheStillWaitingForTheBankBlocksTheSettlement() {
    when(dealService.lock(DEAL_ID)).thenReturn(deal);
    when(trancheRepository.findByDealIdOrderByCreatedAtAsc(DEAL_ID))
        .thenReturn(
            List.of(tranche(TrancheState.CONFIRMED, 6000), tranche(TrancheState.REQUESTED, 4000)));

    assertThatThrownBy(() -> service.settle(DEAL_ID, new SettleDealRequest(0L, 0L, null, null)))
        .isInstanceOfSatisfying(
            PurchasingProblemException.class,
            e ->
                assertThat(e.getProblemCode())
                    .isEqualTo(PurchasingProblemCode.PURCHASING_DEAL_STATE));

    verify(settlementRepository, never()).saveAndFlush(any());
  }

  @ParameterizedTest
  @EnumSource(
      value = DealStatus.class,
      mode = EnumSource.Mode.EXCLUDE,
      names = {"DELIVERED", "FAILED"})
  void aDealThatIsNotWaitingForItsSettlementIsRefused(DealStatus status) {
    deal.setStatus(status);
    when(dealService.lock(DEAL_ID)).thenReturn(deal);

    assertThatThrownBy(() -> service.settle(DEAL_ID, new SettleDealRequest(0L, 0L, null, null)))
        .isInstanceOfSatisfying(
            PurchasingProblemException.class,
            e ->
                assertThat(e.getProblemCode())
                    .isEqualTo(PurchasingProblemCode.PURCHASING_DEAL_STATE));

    verify(trancheRepository, never()).findByDealIdOrderByCreatedAtAsc(any());
    verify(settlementRepository, never()).saveAndFlush(any());
  }

  @Test
  void aPositiveRemainderWithoutAnActiveFundingAccountIsRefused() {
    stubDealWithReceived10000();
    stubNoSettlement();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    when(settingsService.current()).thenReturn(setting);
    when(fundingAccounts.findActive(ACCOUNT)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.settle(DEAL_ID, new SettleDealRequest(7000L, 0L, null, null)))
        .isInstanceOfSatisfying(
            PurchasingProblemException.class,
            e ->
                assertThat(e.getProblemCode())
                    .isEqualTo(PurchasingProblemCode.PURCHASING_FUNDING_ACCOUNT_MISSING));

    verify(purposeBoundRequests, never()).requestDeposit(any(), any(), any());
    verify(settlementRepository, never()).saveAndFlush(any());
  }

  @Test
  void aPositiveRemainderWithoutAnyFundingAccountSetIsRefused() {
    setting.setFundingAccountId(null);
    stubDealWithReceived10000();
    stubNoSettlement();
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
    when(settingsService.current()).thenReturn(setting);

    assertThatThrownBy(() -> service.settle(DEAL_ID, new SettleDealRequest(7000L, 0L, null, null)))
        .isInstanceOfSatisfying(
            PurchasingProblemException.class,
            e ->
                assertThat(e.getProblemCode())
                    .isEqualTo(PurchasingProblemCode.PURCHASING_FUNDING_ACCOUNT_MISSING));

    verify(fundingAccounts, never()).findActive(any());
    verify(settlementRepository, never()).saveAndFlush(any());
  }

  @Test
  void recordingAgainCancelsTheOldDepositRequestAndRaisesTheNewOne() {
    PurchasingDealSettlement existing = existingSettlement(3L);
    stubDealWithReceived10000();
    when(settlementRepository.findByDealId(DEAL_ID)).thenReturn(Optional.of(existing));
    stubRecorder();
    stubFundingAccount();
    when(purposeBoundRequests.requestDeposit(
            ACCOUNT,
            BigDecimal.valueOf(2000L),
            new BankPurpose(BankPurposeKind.PURCHASING_DEAL, DEAL_ID)))
        .thenReturn(NEW_REQUEST);
    stubSaveAndDto();

    service.settle(DEAL_ID, new SettleDealRequest(8000L, 0L, null, 3L));

    verify(purposeBoundRequests).cancel(OLD_REQUEST, BankPurposeKind.PURCHASING_DEAL);
    assertThat(savedSettlement()).isSameAs(existing);
    assertThat(existing.getMoneyPaid()).isEqualTo(8000L);
    assertThat(existing.getRemainder()).isEqualTo(2000L);
    assertThat(existing.getRemainderRequestId()).isEqualTo(NEW_REQUEST);
    assertThat(existing.getSettledAt()).isNull();
    assertThat(deal.getStatus()).isEqualTo(DealStatus.DELIVERED);
  }

  @Test
  void recordingAgainWithAZeroRemainderCancelsTheOldRequestAndSettles() {
    PurchasingDealSettlement existing = existingSettlement(3L);
    stubDealWithReceived10000();
    when(settlementRepository.findByDealId(DEAL_ID)).thenReturn(Optional.of(existing));
    stubRecorder();
    stubSaveAndDto();

    service.settle(DEAL_ID, new SettleDealRequest(10_000L, 0L, null, 3L));

    verify(purposeBoundRequests).cancel(OLD_REQUEST, BankPurposeKind.PURCHASING_DEAL);
    assertThat(existing.getRemainderRequestId()).isNull();
    assertThat(existing.getSettledAt()).isNotNull();
    assertThat(deal.getStatus()).isEqualTo(DealStatus.SETTLED);
  }

  @Test
  void recordingAgainWithAStaleSettlementVersionIsAConflict() {
    PurchasingDealSettlement existing = existingSettlement(3L);
    stubDealWithReceived10000();
    when(settlementRepository.findByDealId(DEAL_ID)).thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> service.settle(DEAL_ID, new SettleDealRequest(8000L, 0L, null, 2L)))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    verify(purposeBoundRequests, never()).cancel(any(), any());
    verify(settlementRepository, never()).saveAndFlush(any());
    assertThat(existing.getRemainderRequestId()).isEqualTo(OLD_REQUEST);
  }

  @Test
  void aConfirmedDepositSettlesTheDeal() {
    PurchasingDealSettlement settlement = existingSettlement(1L);
    settlement.setMoneyPaid(7000L);
    settlement.setLossAmount(0L);
    when(dealService.lock(DEAL_ID)).thenReturn(deal);

    service.onRemainderConfirmed(settlement);

    assertThat(settlement.getSettledAt()).isNotNull();
    assertThat(deal.getStatus()).isEqualTo(DealStatus.SETTLED);
    verify(settlementRepository).saveAndFlush(settlement);
    verify(dealRepository).saveAndFlush(deal);
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.DEAL_SETTLED),
            eq(DEAL_ID),
            eq("Deal 7"),
            isNull(),
            details.capture());
    assertThat(details.getValue().toString()).contains("paid=7000").contains("loss=0");
    assertThat(
            meterRegistry
                .get(MetricNames.PURCHASING_DEALS)
                .tag(MetricNames.TAG_EVENT, "settled")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void aRefusedDepositFreesTheSettlementForANewRecording() {
    PurchasingDealSettlement settlement = existingSettlement(1L);
    when(dealService.lock(DEAL_ID)).thenReturn(deal);

    service.onRemainderNotPaid(settlement, "REJECTED");

    assertThat(settlement.getRemainderRequestId()).isNull();
    assertThat(settlement.getSettledAt()).isNull();
    assertThat(deal.getStatus()).isEqualTo(DealStatus.DELIVERED);
    verify(settlementRepository).saveAndFlush(settlement);
    verify(dealRepository, never()).saveAndFlush(any());
    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.DEAL_SETTLEMENT_RECORDED),
            eq(DEAL_ID),
            eq("Deal 7"),
            isNull(),
            details.capture());
    assertThat(details.getValue().toString())
        .contains("remainder=3000")
        .contains("remainderRequest=REJECTED");
  }

  private void stubDealWithReceived10000() {
    when(dealService.lock(DEAL_ID)).thenReturn(deal);
    when(trancheRepository.findByDealIdOrderByCreatedAtAsc(DEAL_ID))
        .thenReturn(
            List.of(
                tranche(TrancheState.CONFIRMED, 6000),
                tranche(TrancheState.CONFIRMED, 4000),
                tranche(TrancheState.REFUSED, 3000)));
  }

  private void stubNoSettlement() {
    when(settlementRepository.findByDealId(DEAL_ID)).thenReturn(Optional.empty());
  }

  private void stubRecorder() {
    when(authHelperService.currentUserId()).thenReturn(Optional.of(CALLER));
  }

  private void stubFundingAccount() {
    when(settingsService.current()).thenReturn(setting);
    when(fundingAccounts.findActive(ACCOUNT))
        .thenReturn(Optional.of(new FundingAccountRef(ACCOUNT, "KRT-1", "Einkauf", "CARTEL")));
  }

  private void stubSaveAndDto() {
    when(settlementRepository.saveAndFlush(any(PurchasingDealSettlement.class)))
        .thenAnswer(returnsFirstArg());
    when(assembler.toDto(deal)).thenReturn(dto);
  }

  private PurchasingDealSettlement savedSettlement() {
    ArgumentCaptor<PurchasingDealSettlement> captor =
        ArgumentCaptor.forClass(PurchasingDealSettlement.class);
    verify(settlementRepository).saveAndFlush(captor.capture());
    return captor.getValue();
  }

  private void assertInvalid(SettleDealRequest request) {
    ThrowingCallable call = () -> service.settle(DEAL_ID, request);
    assertThatThrownBy(call)
        .isInstanceOfSatisfying(
            PurchasingProblemException.class,
            e ->
                assertThat(e.getProblemCode())
                    .isEqualTo(PurchasingProblemCode.PURCHASING_SETTLEMENT_INVALID));
    verify(settlementRepository, never()).saveAndFlush(any());
    verify(purposeBoundRequests, never()).requestDeposit(any(), any(), any());
    verify(auditRecorder, never()).record(any(), any(), any(), any(), any());
  }

  private static PurchasingDealSettlement existingSettlement(long version) {
    PurchasingDealSettlement settlement = new PurchasingDealSettlement();
    settlement.setId(SETTLEMENT_ID);
    settlement.setVersion(version);
    settlement.setDealId(DEAL_ID);
    settlement.setMoneyReceived(10_000L);
    settlement.setMoneyPaid(7000L);
    settlement.setRemainder(3000L);
    settlement.setRemainderRequestId(OLD_REQUEST);
    return settlement;
  }

  private static PurchasingDealTranche tranche(TrancheState state, long amount) {
    PurchasingDealTranche tranche = new PurchasingDealTranche();
    tranche.setId(UUID.randomUUID());
    tranche.setDealId(DEAL_ID);
    tranche.setKind(TrancheKind.FINAL);
    tranche.setState(state);
    tranche.setAmount(amount);
    return tranche;
  }
}

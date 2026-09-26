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

package de.greluc.krt.profit.basetool.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.repository.AuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAuditEventRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankBookingRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankTransactionRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderItemHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.support.HandleAnonymisation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Mockito unit tests for {@link HandleAnonymisationService}, the granted Art. 17 erasure of a
 * member's handle snapshots (REQ-SEC-062): every column in {@code ANONYMISED_COLUMNS} is reached,
 * text-matched columns run once per spelling and are skipped when no name is known, the marker
 * events follow the updates, and the payload never carries the removed name. The column set itself
 * is pinned by {@code HandleErasureCoverageTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HandleAnonymisationServiceTest {

  private static final UUID USER = UUID.randomUUID();
  private static final String HANDLE = "SomeCallsign";
  private static final String SENTINEL = HandleAnonymisation.SENTINEL;

  /** The common case: one name, because the member set no display name and no nickname. */
  private static final List<String> ONE_SPELLING = List.of(HANDLE);

  @Mock private AuditEventRepository auditEventRepository;
  @Mock private BankAuditEventRepository bankAuditEventRepository;
  @Mock private BankTransactionRepository bankTransactionRepository;
  @Mock private BankBookingRequestRepository bankBookingRequestRepository;
  @Mock private JobOrderHandoverRepository jobOrderHandoverRepository;
  @Mock private JobOrderItemHandoverRepository jobOrderItemHandoverRepository;
  @Mock private BankHolderRepository bankHolderRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private AuditService auditService;
  @Mock private BankAuditService bankAuditService;

  @InjectMocks private HandleAnonymisationService service;

  private void stubCounts(int each) {
    when(auditEventRepository.anonymiseActorHandle(any(), any())).thenReturn(each);
    when(auditEventRepository.anonymiseSubjectLabel(any(), any())).thenReturn(each);
    when(bankAuditEventRepository.anonymiseActorHandle(any(), any())).thenReturn(each);
    when(bankTransactionRepository.anonymiseCounterpartyHandle(any(), any())).thenReturn(each);
    when(bankBookingRequestRepository.anonymiseHandles(any(), any())).thenReturn(each);
    when(bankHolderRepository.anonymiseHandle(any(), any())).thenReturn(each);
    when(jobOrderRepository.anonymiseHandle(any(), any())).thenReturn(each);
    when(jobOrderHandoverRepository.anonymiseRecipientHandle(any(), any())).thenReturn(each);
    when(jobOrderItemHandoverRepository.anonymiseRecipientHandle(any(), any())).thenReturn(each);
  }

  @Test
  void anonymisesEveryPlaceAHandleSurvives() {
    stubCounts(2);

    HandleAnonymisationService.AnonymisationResult result = service.anonymise(USER, ONE_SPELLING);

    verify(auditEventRepository).anonymiseActorHandle(USER, SENTINEL);
    verify(bankAuditEventRepository).anonymiseActorHandle(USER, SENTINEL);
    verify(bankTransactionRepository).anonymiseCounterpartyHandle(USER, SENTINEL);
    verify(bankBookingRequestRepository).anonymiseHandles(USER, SENTINEL);
    verify(bankHolderRepository).anonymiseHandle(USER, SENTINEL);
    verify(auditEventRepository).anonymiseSubjectLabel(HANDLE, SENTINEL);
    verify(jobOrderRepository).anonymiseHandle(HANDLE, SENTINEL);
    verify(jobOrderHandoverRepository).anonymiseRecipientHandle(HANDLE, SENTINEL);
    verify(jobOrderItemHandoverRepository).anonymiseRecipientHandle(HANDLE, SENTINEL);
    assertThat(result.total()).isEqualTo(18);
  }

  @Test
  void runsEveryTextMatchedColumnOncePerSpelling() {
    stubCounts(1);

    service.anonymise(USER, List.of(HANDLE, "TheirUsername", "TheirDiscordNick"));

    for (String spelling : List.of(HANDLE, "TheirUsername", "TheirDiscordNick")) {
      verify(jobOrderHandoverRepository).anonymiseRecipientHandle(spelling, SENTINEL);
      verify(jobOrderItemHandoverRepository).anonymiseRecipientHandle(spelling, SENTINEL);
      verify(jobOrderRepository).anonymiseHandle(spelling, SENTINEL);
      verify(auditEventRepository).anonymiseSubjectLabel(spelling, SENTINEL);
    }
    verify(auditEventRepository).anonymiseActorHandle(USER, SENTINEL);
    verify(bankHolderRepository).anonymiseHandle(USER, SENTINEL);
  }

  @Test
  void deduplicatesIdenticalSpellings() {
    stubCounts(1);

    service.anonymise(USER, List.of(HANDLE, HANDLE, "  " + HANDLE + "  "));

    verify(jobOrderHandoverRepository).anonymiseRecipientHandle(HANDLE, SENTINEL);
    verify(jobOrderRepository).anonymiseHandle(HANDLE, SENTINEL);
  }

  @Test
  void writesTheMarkerEventsAfterTheUpdates() {
    stubCounts(1);

    service.anonymise(USER, ONE_SPELLING);

    InOrder order =
        inOrder(
            auditEventRepository,
            bankAuditEventRepository,
            bankTransactionRepository,
            bankBookingRequestRepository,
            jobOrderRepository,
            auditService,
            bankAuditService);
    order.verify(auditEventRepository).anonymiseActorHandle(USER, SENTINEL);
    order.verify(bankAuditEventRepository).anonymiseActorHandle(USER, SENTINEL);
    order.verify(bankTransactionRepository).anonymiseCounterpartyHandle(USER, SENTINEL);
    order.verify(bankBookingRequestRepository).anonymiseHandles(USER, SENTINEL);
    order.verify(jobOrderRepository).anonymiseHandle(HANDLE, SENTINEL);
    order
        .verify(auditService)
        .record(eq(AuditEventType.HANDLE_SNAPSHOTS_ANONYMISED), eq(USER), any(), eq(USER), any());
    order
        .verify(bankAuditService)
        .record(eq(BankAuditEventType.HANDLE_SNAPSHOTS_ANONYMISED), any(), any(), eq(USER), any());
  }

  @Test
  void theMarkerPayloadNeverCarriesTheErasedHandle() {
    stubCounts(3);

    service.anonymise(USER, ONE_SPELLING);

    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(
            eq(AuditEventType.HANDLE_SNAPSHOTS_ANONYMISED), any(), any(), any(), details.capture());
    assertThat(details.getValue().toString()).doesNotContain(HANDLE);
    assertThat(details.getValue().toString()).contains("activityAudit");
  }

  @Test
  void theMarkerPayloadRecordsTheSpellingCount() {
    stubCounts(1);

    service.anonymise(USER, List.of(HANDLE, "TheirUsername"));

    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(
            eq(AuditEventType.HANDLE_SNAPSHOTS_ANONYMISED), any(), any(), any(), details.capture());
    assertThat(details.getValue().toString()).contains("spellings=2");
    assertThat(details.getValue().toString()).doesNotContain("TheirUsername");
  }

  @Test
  void skipsTheTextMatchedColumnsWhenNoNameIsKnown() {
    stubCounts(1);

    HandleAnonymisationService.AnonymisationResult result = service.anonymise(USER, List.of("   "));

    verify(jobOrderHandoverRepository, never()).anonymiseRecipientHandle(any(), any());
    verify(jobOrderItemHandoverRepository, never()).anonymiseRecipientHandle(any(), any());
    verify(jobOrderRepository, never()).anonymiseHandle(any(), any());
    verify(auditEventRepository, never()).anonymiseSubjectLabel(any(), any());
    assertThat(result.materialHandovers()).isZero();
    assertThat(result.itemHandovers()).isZero();
    assertThat(result.jobOrders()).isZero();
    assertThat(result.total()).isEqualTo(5);
  }

  @Test
  void toleratesNoSpellingsAtAll() {
    stubCounts(0);

    HandleAnonymisationService.AnonymisationResult result = service.anonymise(USER, null);

    assertThat(result.total()).isZero();
    verify(auditEventRepository).anonymiseActorHandle(USER, SENTINEL);
  }
}

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
import de.greluc.krt.profit.basetool.backend.repository.BankTransactionRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderHandoverRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderItemHandoverRepository;
import de.greluc.krt.profit.basetool.backend.support.HandleAnonymisation;
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
 * Mockito unit tests for {@link HandleAnonymisationService} — the granted Art. 17 erasure of a
 * member's surviving handle snapshots (REQ-SEC-062).
 *
 * <p>The properties that carry the requirement: <b>all six</b> places are reached (erasing five of
 * six is worse than erasing none, because the result reads as a completed erasure); the marker
 * events are written <b>after</b> the updates, so the receipt is not scrubbed by the thing it
 * records; the payload never carries the handle that was removed; and the two text-matched handover
 * columns are skipped rather than matched against an empty string when no handle is known.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class HandleAnonymisationServiceTest {

  private static final UUID USER = UUID.randomUUID();
  private static final String HANDLE = "SomeCallsign";
  private static final String SENTINEL = HandleAnonymisation.SENTINEL;

  @Mock private AuditEventRepository auditEventRepository;
  @Mock private BankAuditEventRepository bankAuditEventRepository;
  @Mock private BankTransactionRepository bankTransactionRepository;
  @Mock private BankBookingRequestRepository bankBookingRequestRepository;
  @Mock private JobOrderHandoverRepository jobOrderHandoverRepository;
  @Mock private JobOrderItemHandoverRepository jobOrderItemHandoverRepository;
  @Mock private AuditService auditService;
  @Mock private BankAuditService bankAuditService;

  @InjectMocks private HandleAnonymisationService service;

  private void stubCounts(int each) {
    when(auditEventRepository.anonymiseActorHandle(any(), any())).thenReturn(each);
    when(bankAuditEventRepository.anonymiseActorHandle(any(), any())).thenReturn(each);
    when(bankTransactionRepository.anonymiseCounterpartyHandle(any(), any())).thenReturn(each);
    when(bankBookingRequestRepository.anonymiseHandles(any(), any())).thenReturn(each);
    when(jobOrderHandoverRepository.anonymiseRecipientHandle(any(), any())).thenReturn(each);
    when(jobOrderItemHandoverRepository.anonymiseRecipientHandle(any(), any())).thenReturn(each);
  }

  // covers REQ-SEC-062 — every one of the six places a handle snapshot survives is reached
  @Test
  void anonymisesAllSixPlaces() {
    stubCounts(2);

    HandleAnonymisationService.AnonymisationResult result = service.anonymise(USER, HANDLE);

    verify(auditEventRepository).anonymiseActorHandle(USER, SENTINEL);
    verify(bankAuditEventRepository).anonymiseActorHandle(USER, SENTINEL);
    verify(bankTransactionRepository).anonymiseCounterpartyHandle(USER, SENTINEL);
    verify(bankBookingRequestRepository).anonymiseHandles(USER, SENTINEL);
    verify(jobOrderHandoverRepository).anonymiseRecipientHandle(HANDLE, SENTINEL);
    verify(jobOrderItemHandoverRepository).anonymiseRecipientHandle(HANDLE, SENTINEL);
    assertThat(result.total()).isEqualTo(12);
  }

  // covers REQ-SEC-062 — the receipt is written AFTER the updates, so the update cannot scrub it
  @Test
  void writesTheMarkerEventsAfterTheUpdates() {
    stubCounts(1);

    service.anonymise(USER, HANDLE);

    InOrder order =
        inOrder(
            auditEventRepository,
            bankAuditEventRepository,
            bankTransactionRepository,
            bankBookingRequestRepository,
            auditService,
            bankAuditService);
    order.verify(auditEventRepository).anonymiseActorHandle(USER, SENTINEL);
    order.verify(bankAuditEventRepository).anonymiseActorHandle(USER, SENTINEL);
    order.verify(bankTransactionRepository).anonymiseCounterpartyHandle(USER, SENTINEL);
    order.verify(bankBookingRequestRepository).anonymiseHandles(USER, SENTINEL);
    order
        .verify(auditService)
        .record(eq(AuditEventType.HANDLE_SNAPSHOTS_ANONYMISED), eq(USER), any(), eq(USER), any());
    order
        .verify(bankAuditService)
        .record(eq(BankAuditEventType.HANDLE_SNAPSHOTS_ANONYMISED), any(), any(), eq(USER), any());
  }

  // covers REQ-SEC-062 — writing the erased handle into the receipt would undo the erasure
  @Test
  void theMarkerPayloadNeverCarriesTheErasedHandle() {
    stubCounts(3);

    service.anonymise(USER, HANDLE);

    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(
            eq(AuditEventType.HANDLE_SNAPSHOTS_ANONYMISED), any(), any(), any(), details.capture());
    assertThat(details.getValue().toString()).doesNotContain(HANDLE);
    assertThat(details.getValue().toString()).contains("activityAudit");
  }

  // covers REQ-SEC-062 — matching an empty handle would rewrite every recipient-less handover row
  @Test
  void skipsTheTextMatchedColumnsWhenNoHandleIsKnown() {
    stubCounts(1);

    HandleAnonymisationService.AnonymisationResult result = service.anonymise(USER, "   ");

    verify(jobOrderHandoverRepository, never()).anonymiseRecipientHandle(any(), any());
    verify(jobOrderItemHandoverRepository, never()).anonymiseRecipientHandle(any(), any());
    assertThat(result.materialHandovers()).isZero();
    assertThat(result.itemHandovers()).isZero();
    // The four id-matched places still ran.
    assertThat(result.total()).isEqualTo(4);
  }

  // covers REQ-SEC-062 — a null handle is the already-deleted-account case and must not throw
  @Test
  void toleratesANullHandle() {
    stubCounts(0);

    HandleAnonymisationService.AnonymisationResult result = service.anonymise(USER, null);

    assertThat(result.total()).isZero();
    verify(auditEventRepository).anonymiseActorHandle(USER, SENTINEL);
  }
}

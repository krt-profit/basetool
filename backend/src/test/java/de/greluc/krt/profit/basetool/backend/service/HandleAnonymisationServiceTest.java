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
 * Mockito unit tests for {@link HandleAnonymisationService} — the granted Art. 17 erasure of a
 * member's surviving handle snapshots (REQ-SEC-062).
 *
 * <p>The properties that carry the requirement: <b>every</b> column in {@code
 * HandleAnonymisationService.ANONYMISED_COLUMNS} is reached (erasing seven of eight is worse than
 * erasing none, because the result reads as a completed erasure); every text-matched column is run
 * <b>once per spelling</b>, because a handover is typed by hand and the typist wrote whichever name
 * they call the person; the marker events are written <b>after</b> the updates, so the receipt is
 * not scrubbed by the thing it records; the payload never carries the name that was removed; and
 * the text-matched columns are skipped rather than matched against an empty string when no name is
 * known.
 *
 * <p><b>What this class cannot check is why the set shrank.</b> Three substring {@code REPLACE}
 * statements were removed on 2026-09-17 — over {@code audit_event.details}, {@code
 * bank_audit_event.details} and {@code notification.params} — because they rewrote every row whose
 * text contained a needle the departing member sets on themselves. Mocked repositories cannot see
 * SQL semantics, which is exactly why that defect reached review: the assertions below were green
 * against a statement that could rewrite an unrelated member's rows. Nothing here would have caught
 * it, and nothing here catches its absence either; {@code HandleErasureCoverageTest} is what pins
 * the column set.
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

  // covers REQ-SEC-062 - every place a handle snapshot survives a deletion is reached
  @Test
  void anonymisesEveryPlaceAHandleSurvives() {
    stubCounts(2);

    HandleAnonymisationService.AnonymisationResult result = service.anonymise(USER, ONE_SPELLING);

    // Id-matched, reached while the foreign key still points at the account.
    verify(auditEventRepository).anonymiseActorHandle(USER, SENTINEL);
    verify(bankAuditEventRepository).anonymiseActorHandle(USER, SENTINEL);
    verify(bankTransactionRepository).anonymiseCounterpartyHandle(USER, SENTINEL);
    verify(bankBookingRequestRepository).anonymiseHandles(USER, SENTINEL);
    verify(bankHolderRepository).anonymiseHandle(USER, SENTINEL);
    // Text-matched, because these columns have no user id beside them.
    verify(auditEventRepository).anonymiseSubjectLabel(HANDLE, SENTINEL);
    verify(jobOrderRepository).anonymiseHandle(HANDLE, SENTINEL);
    verify(jobOrderHandoverRepository).anonymiseRecipientHandle(HANDLE, SENTINEL);
    verify(jobOrderItemHandoverRepository).anonymiseRecipientHandle(HANDLE, SENTINEL);
    // Nine statements at 2 rows each -- eight columns, with bank_booking_request's four handle
    // columns rewritten by one statement.
    assertThat(result.total()).isEqualTo(18);
  }

  // covers REQ-SEC-062 - a handover typed with the username is erased as surely as one typed with
  // the display name
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
    // The id-matched ones run once, not once per spelling: repeating them would inflate the
    // receipt's counts while changing nothing on disk.
    verify(auditEventRepository).anonymiseActorHandle(USER, SENTINEL);
    verify(bankHolderRepository).anonymiseHandle(USER, SENTINEL);
  }

  // covers REQ-SEC-062 - a member whose display name equals their username is still one spelling
  @Test
  void deduplicatesIdenticalSpellings() {
    stubCounts(1);

    service.anonymise(USER, List.of(HANDLE, HANDLE, "  " + HANDLE + "  "));

    verify(jobOrderHandoverRepository).anonymiseRecipientHandle(HANDLE, SENTINEL);
    verify(jobOrderRepository).anonymiseHandle(HANDLE, SENTINEL);
  }

  // covers REQ-SEC-062 - the receipt is written AFTER the updates, so the update cannot scrub it
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

  // covers REQ-SEC-062 - writing the erased handle into the receipt would undo the erasure
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

  // covers REQ-SEC-062 - the marker records how many spellings were matched, never the spellings
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

  // covers REQ-SEC-062 - matching an empty name would rewrite every row that has none
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
    // The five id-matched places still ran.
    assertThat(result.total()).isEqualTo(5);
  }

  // covers REQ-SEC-062 - a null collection is the already-deleted-account case and must not throw
  @Test
  void toleratesNoSpellingsAtAll() {
    stubCounts(0);

    HandleAnonymisationService.AnonymisationResult result = service.anonymise(USER, null);

    assertThat(result.total()).isZero();
    verify(auditEventRepository).anonymiseActorHandle(USER, SENTINEL);
  }
}

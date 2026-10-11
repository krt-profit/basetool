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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.platform.api.HandleAnonymisationParticipant.Rewritten;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The bank's part of the handle anonymisation. */
@ExtendWith(MockitoExtension.class)
class BankHandleAnonymisationTest {

  private static final String SENTINEL = HandleAnonymisation.SENTINEL;

  @Mock private BankAuditEventRepository bankAuditEventRepository;
  @Mock private BankTransactionRepository bankTransactionRepository;
  @Mock private BankBookingRequestRepository bankBookingRequestRepository;
  @Mock private BankHolderRepository bankHolderRepository;
  @Mock private BankAuditService bankAuditService;

  @InjectMocks private BankHandleAnonymisation participant;

  private final UUID userId = UUID.randomUUID();

  @Test
  void runsSecondAndRewritesTheSevenBankColumns() {
    assertThat(participant.position()).isEqualTo(20);
    assertThat(participant.columns())
        .containsExactly(
            "bank_audit_event.actor_handle",
            "bank_transaction.counterparty_handle",
            "bank_booking_request.requester_handle",
            "bank_booking_request.decider_handle",
            "bank_booking_request.counterparty_handle",
            "bank_booking_request.owner_approval_granted_by_handle",
            "bank_holder.handle");
  }

  @Test
  void rewritesTheFourTablesByIdInOrderAndIgnoresTheSpellings() {
    when(bankAuditEventRepository.anonymiseActorHandle(userId, SENTINEL)).thenReturn(1);
    when(bankTransactionRepository.anonymiseCounterpartyHandle(userId, SENTINEL)).thenReturn(2);
    when(bankBookingRequestRepository.anonymiseHandles(userId, SENTINEL)).thenReturn(3);
    when(bankHolderRepository.anonymiseHandle(userId, SENTINEL)).thenReturn(4);

    assertThat(participant.anonymise(userId, List.of("Name")))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                Rewritten.BANK_AUDIT, 1,
                Rewritten.BANK_TRANSACTIONS, 2,
                Rewritten.BOOKING_REQUESTS, 3,
                Rewritten.BANK_HOLDERS, 4));

    InOrder order =
        inOrder(
            bankAuditEventRepository,
            bankTransactionRepository,
            bankBookingRequestRepository,
            bankHolderRepository);
    order.verify(bankAuditEventRepository).anonymiseActorHandle(userId, SENTINEL);
    order.verify(bankTransactionRepository).anonymiseCounterpartyHandle(userId, SENTINEL);
    order.verify(bankBookingRequestRepository).anonymiseHandles(userId, SENTINEL);
    order.verify(bankHolderRepository).anonymiseHandle(userId, SENTINEL);
  }

  @Test
  void recordsTheBankMarkerWithTheFourBankCountsOnly() {
    participant.recordAnonymised(
        userId,
        Map.of(
            Rewritten.ACTIVITY_AUDIT, 9,
            Rewritten.BANK_AUDIT, 1,
            Rewritten.BANK_TRANSACTIONS, 2,
            Rewritten.BOOKING_REQUESTS, 3,
            Rewritten.BANK_HOLDERS, 4,
            Rewritten.JOB_ORDERS, 8));

    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.HANDLE_SNAPSHOTS_ANONYMISED),
            isNull(),
            isNull(),
            eq(userId),
            details.capture());
    assertThat(details.getValue().toString())
        .isEqualTo("bankAudit=1 bankTransactions=2 bookingRequests=3 bankHolders=4");
  }
}

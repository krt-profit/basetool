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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankConflictException;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurpose;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurposeKind;
import de.greluc.krt.profit.basetool.backend.bank.api.BookingRequestPurposeObserver;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestCancelledEvent;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;

/**
 * The purpose reference of a booking request and the observer that hears how it ended
 * (REQ-BANK-061, REQ-BANK-063).
 */
@ExtendWith(MockitoExtension.class)
class BankPurposeBoundRequestServiceTest {

  @Mock private BankBookingRequestRepository requestRepository;
  @Mock private BankAccountRepository accountRepository;
  @Mock private BankHolderRepository holderRepository;
  @Mock private BankTransactionRepository transactionRepository;
  @Mock private BankAccountGrantRepository grantRepository;
  @Mock private BankLedgerService bankLedgerService;
  @Mock private BankSecurityService bankSecurityService;
  @Mock private BankAuditService bankAuditService;
  @Mock private AuthHelperService authHelperService;
  @Mock private UserRepository userRepository;
  @Mock private OrgUnitMembershipQueryService orgUnitMembershipQueryService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private ObjectProvider<BookingRequestPurposeObserver> purposeObservers;
  @Mock private BookingRequestPurposeObserver observer;

  @InjectMocks private BankBookingRequestService service;

  private final UUID requestId = UUID.randomUUID();
  private final UUID accountId = UUID.randomUUID();
  private final UUID dealId = UUID.randomUUID();
  private final UUID requester = UUID.randomUUID();

  @BeforeEach
  void observerOfThePurposeKind() {
    lenient().when(observer.kind()).thenReturn(BankPurposeKind.PURCHASING_DEAL);
    lenient().when(purposeObservers.orderedStream()).thenAnswer(invocation -> Stream.of(observer));
  }

  private BankAccount account() {
    BankAccount account = new BankAccount();
    account.setId(accountId);
    account.setAccountNo("KB-0002");
    account.setName("KRT");
    account.setType(BankAccountType.CARTEL);
    account.setStatus(BankAccountStatus.ACTIVE);
    return account;
  }

  private BankBookingRequest pending(BankBookingRequestType type, boolean withPurpose) {
    BankBookingRequest request = new BankBookingRequest();
    request.setId(requestId);
    request.setAccount(account());
    request.setType(type);
    request.setAmount(new BigDecimal("500"));
    request.setStatus(BankBookingRequestStatus.PENDING);
    request.setRequestedBy(requester);
    request.setRequesterHandle("hb");
    request.setVersion(0L);
    if (withPurpose) {
      request.setPurposeKind(BankPurposeKind.PURCHASING_DEAL);
      request.setPurposeRef(dealId);
    }
    return request;
  }

  @Test
  void aRequestCreatedWithAPurposeStoresKindAndReference() {
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account()));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(requester));
    when(requestRepository.save(any(BankBookingRequest.class)))
        .thenAnswer(invocation -> {
          BankBookingRequest saved = invocation.getArgument(0);
          saved.setId(requestId);
          return saved;
        });

    BankBookingRequestDto dto =
        service.create(
            accountId,
            BankBookingRequestType.DEPOSIT,
            new BigDecimal("500"),
            null,
            null,
            null,
            false,
            null,
            null,
            false,
            null,
            null,
            null,
            new BankPurpose(BankPurposeKind.PURCHASING_DEAL, dealId));

    assertThat(dto.purposeKind()).isEqualTo(BankPurposeKind.PURCHASING_DEAL);
    assertThat(dto.purposeRef()).isEqualTo(dealId);
  }

  @Test
  void aRequestCreatedWithoutAPurposeCarriesNone() {
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account()));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(requester));
    when(requestRepository.save(any(BankBookingRequest.class)))
        .thenAnswer(invocation -> {
          BankBookingRequest saved = invocation.getArgument(0);
          saved.setId(requestId);
          return saved;
        });

    BankBookingRequestDto dto =
        service.create(
            accountId,
            BankBookingRequestType.DEPOSIT,
            new BigDecimal("500"),
            null,
            null,
            null,
            false,
            null,
            null,
            false,
            null,
            null,
            null);

    assertThat(dto.purposeKind()).isNull();
    assertThat(dto.purposeRef()).isNull();
  }

  private UUID confirmSetup(BankBookingRequest request, UUID decider, UUID txId) {
    UUID holderId = UUID.randomUUID();
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    when(bankSecurityService.canConfirm(eq(BankBookingRequestType.DEPOSIT), eq(accountId), any()))
        .thenReturn(true);
    when(bankLedgerService.bookDeposit(any(BankDepositRequest.class)))
        .thenReturn(
            new BankTransactionDto(txId, BankTransactionType.DEPOSIT, "from sale", Instant.now()));
    BankHolder holder = new BankHolder();
    holder.setId(holderId);
    holder.setHandle("holder");
    holder.setActive(true);
    when(holderRepository.findById(holderId)).thenReturn(Optional.of(holder));
    BankTransaction transaction = new BankTransaction();
    transaction.setId(txId);
    when(transactionRepository.findById(txId)).thenReturn(Optional.of(transaction));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(decider));
    return holderId;
  }

  @Test
  void aConfirmedPurposeBoundRequestIsReportedToItsObserverWithTheTransaction() {
    BankBookingRequest request = pending(BankBookingRequestType.DEPOSIT, true);
    UUID txId = UUID.randomUUID();
    UUID holderId = confirmSetup(request, UUID.randomUUID(), txId);

    service.confirm(requestId, holderId, null, false, null, 0L, null);

    verify(observer).onConfirmed(requestId, dealId, txId);
  }

  @Test
  void aConfirmedPlainRequestNeverAsksForObservers() {
    BankBookingRequest request = pending(BankBookingRequestType.DEPOSIT, false);
    UUID holderId = confirmSetup(request, UUID.randomUUID(), UUID.randomUUID());

    service.confirm(requestId, holderId, null, false, null, 0L, null);

    verify(purposeObservers, never()).orderedStream();
    verify(observer, never()).onConfirmed(any(), any(), any());
  }

  @Test
  void anObserverThatFailsFailsTheConfirmation() {
    BankBookingRequest request = pending(BankBookingRequestType.DEPOSIT, true);
    UUID holderId = confirmSetup(request, UUID.randomUUID(), UUID.randomUUID());
    doThrow(new IllegalStateException("deal state is wrong"))
        .when(observer)
        .onConfirmed(any(), any(), any());

    assertThrows(
        IllegalStateException.class,
        () -> service.confirm(requestId, holderId, null, false, null, 0L, null));
  }

  @Test
  void aRejectedPurposeBoundRequestIsReportedToItsObserver() {
    BankBookingRequest request = pending(BankBookingRequestType.DEPOSIT, true);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    when(bankSecurityService.canSee(eq(accountId), any())).thenReturn(true);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(UUID.randomUUID()));

    service.reject(requestId, "duplicate", 0L, null);

    verify(observer).onRejected(requestId, dealId);
  }

  @Test
  void aCancelledPurposeBoundRequestIsReportedToItsObserver() {
    BankBookingRequest request = pending(BankBookingRequestType.DEPOSIT, true);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(requester));

    service.cancelOwn(requestId, 0L);

    assertThat(request.getStatus()).isEqualTo(BankBookingRequestStatus.CANCELLED);
    verify(observer).onCancelled(requestId, dealId);
  }

  @Test
  void theOwnerOfAPurposeCancelsItsRequestAndHearsOfItLikeAnyOther() {
    BankBookingRequest request = pending(BankBookingRequestType.WITHDRAWAL, true);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(requester));

    service.cancelForPurpose(requestId, BankPurposeKind.PURCHASING_DEAL);

    assertThat(request.getStatus()).isEqualTo(BankBookingRequestStatus.CANCELLED);
    assertThat(request.getDecidedAt()).isNotNull();
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.BOOKING_REQUEST_CANCELLED),
            eq(accountId),
            eq(null),
            eq(requester),
            any());
    verify(observer).onCancelled(requestId, dealId);
    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(published.capture());
    assertThat(published.getValue()).isInstanceOf(BankBookingRequestCancelledEvent.class);
  }

  @Test
  void aRequestWithoutThePurposeKindIsNotFoundForTheOwnerOfThatKind() {
    BankBookingRequest request = pending(BankBookingRequestType.WITHDRAWAL, false);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));

    assertThrows(
        NotFoundException.class,
        () -> service.cancelForPurpose(requestId, BankPurposeKind.PURCHASING_DEAL));
    assertThat(request.getStatus()).isEqualTo(BankBookingRequestStatus.PENDING);
  }

  @Test
  void aDecidedRequestCannotBeCancelledByItsOwner() {
    BankBookingRequest request = pending(BankBookingRequestType.WITHDRAWAL, true);
    request.setStatus(BankBookingRequestStatus.CONFIRMED);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));

    assertThrows(
        BankConflictException.class,
        () -> service.cancelForPurpose(requestId, BankPurposeKind.PURCHASING_DEAL));
    verify(observer, never()).onCancelled(any(), any());
  }
}

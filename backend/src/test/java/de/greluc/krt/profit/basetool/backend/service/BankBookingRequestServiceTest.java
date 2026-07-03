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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.event.BankBookingRequestConfirmedEvent;
import de.greluc.krt.profit.basetool.backend.event.BankBookingRequestCreatedEvent;
import de.greluc.krt.profit.basetool.backend.event.BankBookingRequestRejectedEvent;
import de.greluc.krt.profit.basetool.backend.exception.BankConflictException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAccountType;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import de.greluc.krt.profit.basetool.backend.model.BankRequestApprover;
import de.greluc.krt.profit.basetool.backend.model.BankTransaction;
import de.greluc.krt.profit.basetool.backend.model.BankTransactionType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankTransactionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankDepositRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankTransferRequest;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountGrantRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankBookingRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankTransactionRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit tests for {@link BankBookingRequestService} — the F2 lifecycle engine (REQ-BANK-022/-023):
 * create (audit + notification event), cancel (ownership + pending guards), confirm (capability +
 * ledger reuse + state flip), reject and the close-account input. The ledger / overdraft mechanics
 * themselves are pinned by {@code BankLedgerServiceTest}; here the ledger is mocked and we assert
 * the request orchestration around it.
 */
@ExtendWith(MockitoExtension.class)
class BankBookingRequestServiceTest {

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
  @Mock private OrgUnitMembershipService orgUnitMembershipService;
  @Mock private ApplicationEventPublisher eventPublisher;

  @InjectMocks private BankBookingRequestService service;

  private static BankAccount account(UUID id) {
    BankAccount account = new BankAccount();
    account.setId(id);
    account.setAccountNo("KB-0001");
    account.setName("Staffel IRIDIUM");
    account.setType(BankAccountType.ORG_UNIT);
    account.setStatus(BankAccountStatus.ACTIVE);
    Squadron squadron = new Squadron();
    squadron.setId(UUID.randomUUID());
    squadron.setName("IRIDIUM");
    squadron.setShorthand("IRI");
    account.setOrgUnit(squadron);
    return account;
  }

  /**
   * Builds an active {@code CARTEL} account — a {@linkplain
   * BankAccountType#requiresDebitJustification() justification-mandating} type with no owning org
   * unit — for the REQ-BANK-045 Begr&uuml;ndung tests.
   *
   * @param id the account id
   * @return an active CARTEL account
   */
  private static BankAccount cartelAccount(UUID id) {
    BankAccount account = new BankAccount();
    account.setId(id);
    account.setAccountNo("KB-0002");
    account.setName("KRT");
    account.setType(BankAccountType.CARTEL);
    account.setStatus(BankAccountStatus.ACTIVE);
    return account;
  }

  private static BankBookingRequest pending(
      UUID id, BankAccount account, BankBookingRequestType type, UUID requestedBy, long version) {
    BankBookingRequest request = new BankBookingRequest();
    request.setId(id);
    request.setAccount(account);
    request.setType(type);
    request.setAmount(new BigDecimal("500"));
    request.setStatus(BankBookingRequestStatus.PENDING);
    request.setRequestedBy(requestedBy);
    request.setRequesterHandle("requester");
    request.setVersion(version);
    return request;
  }

  @Test
  void create_persistsPendingAuditsAndPublishesEvent() {
    UUID accountId = UUID.randomUUID();
    UUID requesterSub = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(requesterSub));
    User user = new User();
    user.setUsername("officer");
    when(userRepository.findById(requesterSub)).thenReturn(Optional.of(user));
    when(requestRepository.save(any(BankBookingRequest.class)))
        .thenAnswer(
            invocation -> {
              BankBookingRequest saved = invocation.getArgument(0);
              saved.setId(UUID.randomUUID());
              return saved;
            });

    BankBookingRequestDto dto =
        service.create(
            accountId,
            BankBookingRequestType.DEPOSIT,
            new BigDecimal("500"),
            "from sale",
            null,
            null,
            false,
            null,
            null,
            false,
            null);

    assertThat(dto.status()).isEqualTo(BankBookingRequestStatus.PENDING);
    assertThat(dto.type()).isEqualTo(BankBookingRequestType.DEPOSIT);
    verify(bankAuditService)
        .record(
            eq(
                de.greluc.krt.profit.basetool.backend.model.BankAuditEventType
                    .BOOKING_REQUEST_CREATED),
            eq(accountId),
            eq(null),
            eq(requesterSub),
            any());
    ArgumentCaptor<BankBookingRequestCreatedEvent> event =
        ArgumentCaptor.forClass(BankBookingRequestCreatedEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertThat(event.getValue().accountId()).isEqualTo(accountId);
    assertThat(event.getValue().contextAccountId()).isEqualTo(accountId);
    assertThat(event.getValue().actorSub()).isEqualTo(requesterSub);
  }

  @Test
  void create_splitDeposit_snapshotsSplitOnRequest() {
    // REQ-BANK-043: a split deposit request snapshots split_enabled + split_percent on the
    // off-ledger
    // row; the concrete per-squadron legs are resolved only at confirmation.
    UUID accountId = UUID.randomUUID();
    UUID requesterSub = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(requesterSub));
    when(userRepository.findById(requesterSub)).thenReturn(Optional.of(new User()));
    ArgumentCaptor<BankBookingRequest> saved = ArgumentCaptor.forClass(BankBookingRequest.class);
    when(requestRepository.save(saved.capture()))
        .thenAnswer(
            invocation -> {
              BankBookingRequest r = invocation.getArgument(0);
              r.setId(UUID.randomUUID());
              return r;
            });

    service.create(
        accountId,
        BankBookingRequestType.DEPOSIT,
        new BigDecimal("1000"),
        "from sale",
        null,
        null,
        false,
        null,
        null,
        true,
        new BigDecimal("30"));

    assertThat(saved.getValue().isSplitEnabled()).isTrue();
    assertThat(saved.getValue().getSplitPercent()).isEqualByComparingTo(new BigDecimal("30"));
  }

  @Test
  void create_rejectsClosedAccount() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId);
    account.setStatus(BankAccountStatus.CLOSED);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () ->
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
                    null));
    assertThat(ex.getCode()).isEqualTo(BankConflictException.CODE_BANK_ACCOUNT_CLOSED);
    verify(requestRepository, never()).save(any());
  }

  @Test
  void create_withdrawalFromMandatingAccount_blankJustification_rejected() {
    // REQ-BANK-045: a withdrawal/transfer request leaving a CARTEL / CARTEL_BANK / SPECIAL account
    // must carry a non-blank Begründung; a blank one is rejected with BANK_JUSTIFICATION_REQUIRED
    // and nothing is persisted.
    UUID accountId = UUID.randomUUID();
    BankAccount account = cartelAccount(accountId);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () ->
                service.create(
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("500"),
                    null,
                    "   ",
                    null,
                    true,
                    null,
                    BankRequestApprover.RESPONSIBLE_HOLDER,
                    false,
                    null));
    assertThat(ex.getCode()).isEqualTo(BankConflictException.CODE_BANK_JUSTIFICATION_REQUIRED);
    verify(requestRepository, never()).save(any());
  }

  @Test
  void create_withdrawalFromOrgUnitAccount_blankJustification_succeeds() {
    // REQ-BANK-045: an ORG_UNIT (or AREA) account leaves the Begründung optional — a withdrawal
    // request without one is accepted and persists a null justification.
    UUID accountId = UUID.randomUUID();
    UUID requesterSub = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(requesterSub));
    when(userRepository.findById(requesterSub)).thenReturn(Optional.of(new User()));
    ArgumentCaptor<BankBookingRequest> saved = ArgumentCaptor.forClass(BankBookingRequest.class);
    when(requestRepository.save(saved.capture()))
        .thenAnswer(
            invocation -> {
              BankBookingRequest r = invocation.getArgument(0);
              r.setId(UUID.randomUUID());
              return r;
            });

    service.create(
        accountId,
        BankBookingRequestType.WITHDRAWAL,
        new BigDecimal("500"),
        null,
        null,
        null,
        true,
        null,
        BankRequestApprover.RESPONSIBLE_HOLDER,
        false,
        null);

    assertThat(saved.getValue().getJustification()).isNull();
  }

  @Test
  void create_withdrawalFromMandatingAccount_persistsJustification() {
    // REQ-BANK-045: a non-blank Begründung is snapshotted on the request so it can be carried onto
    // the booking at confirmation.
    UUID accountId = UUID.randomUUID();
    UUID requesterSub = UUID.randomUUID();
    BankAccount account = cartelAccount(accountId);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(requesterSub));
    when(userRepository.findById(requesterSub)).thenReturn(Optional.of(new User()));
    ArgumentCaptor<BankBookingRequest> saved = ArgumentCaptor.forClass(BankBookingRequest.class);
    when(requestRepository.save(saved.capture()))
        .thenAnswer(
            invocation -> {
              BankBookingRequest r = invocation.getArgument(0);
              r.setId(UUID.randomUUID());
              return r;
            });

    service.create(
        accountId,
        BankBookingRequestType.WITHDRAWAL,
        new BigDecimal("500"),
        null,
        "Reparaturkosten",
        null,
        true,
        null,
        BankRequestApprover.RESPONSIBLE_HOLDER,
        false,
        null);

    assertThat(saved.getValue().getJustification()).isEqualTo("Reparaturkosten");
  }

  @Test
  void cancelOwn_byRequester_flipsCancelled() {
    UUID requestId = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            requestId, account(UUID.randomUUID()), BankBookingRequestType.DEPOSIT, requester, 0L);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(requester));

    BankBookingRequestDto dto = service.cancelOwn(requestId, 0L);

    assertThat(dto.status()).isEqualTo(BankBookingRequestStatus.CANCELLED);
    verify(bankAuditService)
        .record(
            eq(
                de.greluc.krt.profit.basetool.backend.model.BankAuditEventType
                    .BOOKING_REQUEST_CANCELLED),
            any(),
            eq(null),
            eq(requester),
            any());
  }

  @Test
  void cancelOwn_byForeignUser_throwsNotFound() {
    UUID requestId = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            requestId,
            account(UUID.randomUUID()),
            BankBookingRequestType.DEPOSIT,
            UUID.randomUUID(),
            0L);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(UUID.randomUUID()));

    assertThrows(NotFoundException.class, () -> service.cancelOwn(requestId, 0L));
    assertThat(request.getStatus()).isEqualTo(BankBookingRequestStatus.PENDING);
  }

  @Test
  void cancelOwn_alreadyDecided_throwsConflict() {
    UUID requestId = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            requestId, account(UUID.randomUUID()), BankBookingRequestType.DEPOSIT, requester, 1L);
    request.setStatus(BankBookingRequestStatus.CONFIRMED);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(requester));

    BankConflictException ex =
        assertThrows(BankConflictException.class, () -> service.cancelOwn(requestId, 1L));
    assertThat(ex.getCode()).isEqualTo(BankConflictException.CODE_BANK_REQUEST_NOT_PENDING);
  }

  @Test
  void confirm_deposit_booksAndConfirms() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID holderId = UUID.randomUUID();
    UUID txId = UUID.randomUUID();
    UUID decider = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    BankBookingRequest request =
        pending(requestId, account(accountId), BankBookingRequestType.DEPOSIT, requester, 0L);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    when(bankSecurityService.canDeposit(eq(accountId), any())).thenReturn(true);
    when(bankLedgerService.bookDeposit(any(BankDepositRequest.class)))
        .thenReturn(
            new BankTransactionDto(txId, BankTransactionType.DEPOSIT, "from sale", Instant.now()));
    BankHolder holder = new BankHolder();
    holder.setId(holderId);
    holder.setHandle("greluc");
    holder.setActive(true);
    when(holderRepository.findById(holderId)).thenReturn(Optional.of(holder));
    BankTransaction tx = new BankTransaction();
    when(transactionRepository.findById(txId)).thenReturn(Optional.of(tx));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(decider));
    when(userRepository.findById(decider)).thenReturn(Optional.of(new User()));
    // REQ-BANK-044: the confirmed booking records the requester (Einzahler) plus their primary
    // unit.
    UUID requesterOrgUnit = UUID.randomUUID();
    when(orgUnitMembershipService.findPrimaryDirectMembershipOrgUnitId(requester))
        .thenReturn(Optional.of(requesterOrgUnit));

    BankBookingRequestDto dto = service.confirm(requestId, holderId, null, false, 0L, null);

    assertThat(dto.status()).isEqualTo(BankBookingRequestStatus.CONFIRMED);
    assertThat(dto.holderId()).isEqualTo(holderId);
    ArgumentCaptor<BankDepositRequest> booked = ArgumentCaptor.forClass(BankDepositRequest.class);
    verify(bankLedgerService).bookDeposit(booked.capture());
    assertThat(booked.getValue().accountId()).isEqualTo(accountId);
    assertThat(booked.getValue().holderId()).isEqualTo(holderId);
    // The requester becomes the counterparty, with their resolved primary org unit (REQ-BANK-044).
    assertThat(booked.getValue().counterpartyUserId()).isEqualTo(requester);
    assertThat(booked.getValue().counterpartyOrgUnitId()).isEqualTo(requesterOrgUnit);
    verify(bankAuditService)
        .record(
            eq(
                de.greluc.krt.profit.basetool.backend.model.BankAuditEventType
                    .BOOKING_REQUEST_CONFIRMED),
            eq(accountId),
            eq(txId),
            eq(requester),
            any());
    ArgumentCaptor<BankBookingRequestConfirmedEvent> event =
        ArgumentCaptor.forClass(BankBookingRequestConfirmedEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    // The non-split deposit hands a non-split payload to the ledger.
    assertThat(booked.getValue().splitEnabled()).isFalse();
    assertThat(event.getValue().contextRecipientSub()).isEqualTo(requester);
    assertThat(event.getValue().actorSub()).isEqualTo(decider);
    // REQ-BANK-026/-034: the event carries the account id so the ACCOUNT_RESPONSIBLE selector can
    // notify the account's responsible holder.
    assertThat(event.getValue().contextAccountId()).isEqualTo(accountId);
  }

  @Test
  void confirm_splitDeposit_booksWithSplitSnapshot() {
    // REQ-BANK-043: confirming a split deposit request books via bookDeposit carrying the
    // snapshotted
    // percentage; the ledger resolves the concrete legs against the squadron accounts active now.
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID holderId = UUID.randomUUID();
    UUID txId = UUID.randomUUID();
    UUID decider = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    BankBookingRequest request =
        pending(requestId, account(accountId), BankBookingRequestType.DEPOSIT, requester, 0L);
    request.setAmount(new BigDecimal("1000"));
    request.setSplitEnabled(true);
    request.setSplitPercent(new BigDecimal("30"));
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    when(bankSecurityService.canDeposit(eq(accountId), any())).thenReturn(true);
    when(bankLedgerService.bookDeposit(any(BankDepositRequest.class)))
        .thenReturn(new BankTransactionDto(txId, BankTransactionType.DEPOSIT, null, Instant.now()));
    BankHolder holder = new BankHolder();
    holder.setId(holderId);
    holder.setHandle("greluc");
    holder.setActive(true);
    when(holderRepository.findById(holderId)).thenReturn(Optional.of(holder));
    when(transactionRepository.findById(txId)).thenReturn(Optional.of(new BankTransaction()));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(decider));
    when(userRepository.findById(decider)).thenReturn(Optional.of(new User()));

    service.confirm(requestId, holderId, null, false, 0L, null);

    ArgumentCaptor<BankDepositRequest> booked = ArgumentCaptor.forClass(BankDepositRequest.class);
    verify(bankLedgerService).bookDeposit(booked.capture());
    assertThat(booked.getValue().splitEnabled()).isTrue();
    assertThat(booked.getValue().splitPercent()).isEqualByComparingTo(new BigDecimal("30"));
    assertThat(booked.getValue().accountId()).isEqualTo(accountId);
    assertThat(booked.getValue().holderId()).isEqualTo(holderId);
  }

  @Test
  void confirm_withoutCapability_throwsAccessDenied() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            requestId,
            account(accountId),
            BankBookingRequestType.WITHDRAWAL,
            UUID.randomUUID(),
            0L);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    when(bankSecurityService.canWithdraw(eq(accountId), any())).thenReturn(false);

    assertThrows(
        AccessDeniedException.class,
        () -> service.confirm(requestId, UUID.randomUUID(), null, false, 0L, null));
    verify(bankLedgerService, never()).bookWithdrawal(any());
  }

  @Test
  void confirm_alreadyDecided_throwsConflict() {
    UUID requestId = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            requestId,
            account(UUID.randomUUID()),
            BankBookingRequestType.DEPOSIT,
            UUID.randomUUID(),
            2L);
    request.setStatus(BankBookingRequestStatus.REJECTED);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () -> service.confirm(requestId, UUID.randomUUID(), null, false, 2L, null));
    assertThat(ex.getCode()).isEqualTo(BankConflictException.CODE_BANK_REQUEST_NOT_PENDING);
    verify(bankLedgerService, never()).bookDeposit(any());
  }

  @Test
  void confirm_versionMismatch_throwsOptimisticLock() {
    UUID requestId = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            requestId,
            account(UUID.randomUUID()),
            BankBookingRequestType.DEPOSIT,
            UUID.randomUUID(),
            5L);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> service.confirm(requestId, UUID.randomUUID(), null, false, 4L, null));
    verify(bankLedgerService, never()).bookDeposit(any());
  }

  @Test
  void reject_flipsRejectedWithReason() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID decider = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            requestId, account(accountId), BankBookingRequestType.DEPOSIT, UUID.randomUUID(), 0L);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    when(bankSecurityService.canSee(eq(accountId), any())).thenReturn(true);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(decider));
    when(userRepository.findById(decider)).thenReturn(Optional.of(new User()));

    BankBookingRequestDto dto = service.reject(requestId, "duplicate", 0L, null);

    assertThat(dto.status()).isEqualTo(BankBookingRequestStatus.REJECTED);
    assertThat(dto.rejectReason()).isEqualTo("duplicate");
    verify(bankLedgerService, never()).bookDeposit(any());
    ArgumentCaptor<BankBookingRequestRejectedEvent> event =
        ArgumentCaptor.forClass(BankBookingRequestRejectedEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertThat(event.getValue().reason()).isEqualTo("duplicate");
    // REQ-BANK-026/-034: the event carries the account id for the ACCOUNT_RESPONSIBLE selector.
    assertThat(event.getValue().contextAccountId()).isEqualTo(accountId);
  }

  @Test
  void confirm_overLimitWithoutApproval_throwsConflict() {
    UUID requestId = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            requestId,
            account(UUID.randomUUID()),
            BankBookingRequestType.DEPOSIT,
            UUID.randomUUID(),
            0L);
    request.setRequiresOwnerApproval(true);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () -> service.confirm(requestId, UUID.randomUUID(), null, false, 0L, null));
    assertThat(ex.getCode()).isEqualTo(BankConflictException.CODE_BANK_OWNER_APPROVAL_REQUIRED);
    verify(bankLedgerService, never()).bookDeposit(any());
  }

  @Test
  void confirm_overLimitWithApproval_booksAndAuditsOwnerApproval() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID holderId = UUID.randomUUID();
    UUID txId = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    BankBookingRequest request =
        pending(requestId, account(accountId), BankBookingRequestType.DEPOSIT, requester, 0L);
    request.setRequiresOwnerApproval(true);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    when(bankSecurityService.canDeposit(eq(accountId), any())).thenReturn(true);
    when(bankLedgerService.bookDeposit(any(BankDepositRequest.class)))
        .thenReturn(new BankTransactionDto(txId, BankTransactionType.DEPOSIT, null, Instant.now()));
    BankHolder holder = new BankHolder();
    holder.setId(holderId);
    holder.setHandle("greluc");
    holder.setActive(true);
    when(holderRepository.findById(holderId)).thenReturn(Optional.of(holder));
    when(transactionRepository.findById(txId)).thenReturn(Optional.of(new BankTransaction()));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(UUID.randomUUID()));
    when(userRepository.findById(any())).thenReturn(Optional.of(new User()));

    BankBookingRequestDto dto = service.confirm(requestId, holderId, null, true, 0L, null);

    assertThat(dto.status()).isEqualTo(BankBookingRequestStatus.CONFIRMED);
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.BOOKING_REQUEST_OWNER_APPROVAL_CONFIRMED),
            eq(accountId),
            eq(txId),
            eq(requester),
            any());
  }

  @Test
  void confirm_transfer_bypassesDestinationVisibilityGate() {
    // REQ-BANK-040 (review F1): confirming a transfer *request* must not require the employee to
    // hold a grant on the destination — the requester already chose it from any active account.
    // The confirm path therefore never consults canSee(destination) and always books with
    // destinationVisible = true, so a scoped employee cannot hit a permanent dead-end.
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID destId = UUID.randomUUID();
    UUID sourceHolder = UUID.randomUUID();
    UUID destHolder = UUID.randomUUID();
    UUID txId = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            requestId, account(accountId), BankBookingRequestType.TRANSFER, UUID.randomUUID(), 0L);
    request.setTargetAccount(account(destId));
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    when(bankSecurityService.canTransfer(eq(accountId), any())).thenReturn(true);
    when(bankLedgerService.bookTransfer(any(BankTransferRequest.class), anyBoolean()))
        .thenReturn(
            new BankTransactionDto(txId, BankTransactionType.TRANSFER, null, Instant.now()));
    BankHolder holder = new BankHolder();
    holder.setId(sourceHolder);
    holder.setHandle("greluc");
    holder.setActive(true);
    when(holderRepository.findById(sourceHolder)).thenReturn(Optional.of(holder));
    when(transactionRepository.findById(txId)).thenReturn(Optional.of(new BankTransaction()));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(UUID.randomUUID()));
    when(userRepository.findById(any())).thenReturn(Optional.of(new User()));

    BankBookingRequestDto dto =
        service.confirm(requestId, sourceHolder, destHolder, false, 0L, null);

    assertThat(dto.status()).isEqualTo(BankBookingRequestStatus.CONFIRMED);
    ArgumentCaptor<BankTransferRequest> booked = ArgumentCaptor.forClass(BankTransferRequest.class);
    verify(bankLedgerService).bookTransfer(booked.capture(), eq(true));
    verify(bankSecurityService, never()).canSee(any(), any());
    assertThat(booked.getValue().sourceAccountId()).isEqualTo(accountId);
    assertThat(booked.getValue().destinationAccountId()).isEqualTo(destId);
    assertThat(booked.getValue().sourceHolderId()).isEqualTo(sourceHolder);
    assertThat(booked.getValue().destinationHolderId()).isEqualTo(destHolder);
  }

  @Test
  void applyOwnerApprovalWithinTransaction_grant_setsFlagAndAudits() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    UUID approver = UUID.randomUUID();
    BankBookingRequest request =
        pending(requestId, account(accountId), BankBookingRequestType.DEPOSIT, requester, 0L);
    request.setRequiresOwnerApproval(true);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(approver));
    when(userRepository.findById(approver)).thenReturn(Optional.of(new User()));

    BankBookingRequestDto dto = service.applyOwnerApprovalWithinTransaction(request, true);

    assertThat(dto.ownerApprovalGranted()).isTrue();
    assertThat(request.isOwnerApprovalGranted()).isTrue();
    assertThat(request.getOwnerApprovalGrantedBy()).isEqualTo(approver);
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.BOOKING_REQUEST_OWNER_APPROVAL_GRANTED),
            eq(accountId),
            eq(null),
            eq(requester),
            any());
  }

  @Test
  void hasOpenRequests_delegatesToRepository() {
    UUID accountId = UUID.randomUUID();
    when(requestRepository.existsByAccountIdAndStatus(accountId, BankBookingRequestStatus.PENDING))
        .thenReturn(true);

    assertThat(service.hasOpenRequests(accountId)).isTrue();
  }
}

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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.api.BankAdmission;
import de.greluc.krt.profit.basetool.backend.bank.api.BankApproverNoteRequiredException;
import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankConflictException;
import de.greluc.krt.profit.basetool.backend.bank.api.BankRequestApprover;
import de.greluc.krt.profit.basetool.backend.bank.api.BookingRequestPurposeObserver;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestCancelledEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestConfirmedEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestCreatedEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestRejectedEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestUpdatedEvent;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationEventType;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NoticeEvent;
import de.greluc.krt.profit.basetool.backend.orgunit.api.BereichRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit tests for {@link BankBookingRequestService} (REQ-BANK-022): create, cancel, confirm, reject
 * and close-account orchestration, with the ledger mocked and a real {@link BankAccessRules}
 * deciding for the mocked caller's actor.
 */
@ExtendWith(MockitoExtension.class)
class BankBookingRequestServiceTest {

  @Mock private BankBookingRequestRepository requestRepository;
  @Mock private BankAccountRepository accountRepository;
  @Mock private BankHolderRepository holderRepository;
  @Mock private BankTransactionRepository transactionRepository;
  @Mock private BankLedgerService bankLedgerService;
  @Mock private BankCaller bankCaller;
  @Mock private BankAccountViewGrantRepository viewGrantRepository;
  @Mock private BankAccountApprovalLimitRepository approvalLimitRepository;
  @Mock private BereichRepository bereichRepository;
  @Mock private OrgUnitRepository orgUnitRepository;
  @Mock private BankAuditService bankAuditService;
  @Mock private AuthHelperService authHelperService;
  @Mock private UserRepository userRepository;
  @Mock private OrgUnitMembershipQueryService orgUnitMembershipQueryService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private ObjectProvider<BookingRequestPurposeObserver> purposeObservers;
  @Mock private BankRequestClientNames clientNames;

  private BankAccessRules policy;
  private BankBookingRequestService service;

  @BeforeEach
  void wireTheServiceOverTheRealPolicy() {
    policy =
        new BankAccessRules(
            accountRepository,
            viewGrantRepository,
            approvalLimitRepository,
            bereichRepository,
            orgUnitRepository);
    service =
        new BankBookingRequestService(
            requestRepository,
            accountRepository,
            holderRepository,
            transactionRepository,
            bankLedgerService,
            bankCaller,
            policy,
            bankAuditService,
            authHelperService,
            userRepository,
            orgUnitMembershipQueryService,
            eventPublisher,
            purposeObservers,
            clientNames);
  }

  @Test
  void listQueue_emptyStatuses_returnsEmptyWithoutQuerying() {
    Page<BankBookingRequestDto> result = service.listQueue(Set.of(), PageRequest.of(0, 20));

    assertThat(result.getTotalElements()).isZero();
    verify(requestRepository, never()).findByStatusIn(any(), any());
    verify(requestRepository, never()).findByStatusInAndAccountIdIn(any(), any(), any());
    verifyNoInteractions(bankCaller);
  }

  @Test
  void listQueue_management_delegatesToFindByStatusInAcrossAllAccounts() {
    Set<BankBookingRequestStatus> statuses =
        Set.of(BankBookingRequestStatus.PENDING, BankBookingRequestStatus.CONFIRMED);
    callerIs(TestBankActor.of(UUID.randomUUID()).bankManagement().build());
    when(requestRepository.findByStatusIn(eq(statuses), any(Pageable.class)))
        .thenReturn(Page.<BankBookingRequest>empty());

    service.listQueue(statuses, PageRequest.of(0, 20));

    verify(requestRepository).findByStatusIn(eq(statuses), any(Pageable.class));
    verify(requestRepository, never()).findByStatusInAndAccountIdIn(any(), any(), any());
  }

  /**
   * REQ-BANK-023: every queue row says whether the caller may confirm it, by the confirm endpoint's
   * capability rule per type and account; a decided row never offers the action.
   */
  @Test
  void listQueue_marksEachRowWithTheConfirmCapability() {
    UUID grantedAccount = UUID.randomUUID();
    UUID otherAccount = UUID.randomUUID();
    BankBookingRequest depositOnGranted =
        pending(
            UUID.randomUUID(),
            account(grantedAccount),
            BankBookingRequestType.DEPOSIT,
            UUID.randomUUID(),
            0L);
    BankBookingRequest withdrawalOnGranted =
        pending(
            UUID.randomUUID(),
            account(grantedAccount),
            BankBookingRequestType.WITHDRAWAL,
            UUID.randomUUID(),
            0L);
    BankBookingRequest depositOnOther =
        pending(
            UUID.randomUUID(),
            account(otherAccount),
            BankBookingRequestType.DEPOSIT,
            UUID.randomUUID(),
            0L);
    BankBookingRequest decidedDeposit =
        pending(
            UUID.randomUUID(),
            account(grantedAccount),
            BankBookingRequestType.DEPOSIT,
            UUID.randomUUID(),
            1L);
    decidedDeposit.setStatus(BankBookingRequestStatus.CONFIRMED);
    callerIs(employeeMayBook(UUID.randomUUID(), grantedAccount, BankBookingRequestType.DEPOSIT));
    when(requestRepository.findByStatusInAndAccountIdIn(
            any(), eq(Set.of(grantedAccount)), any(Pageable.class)))
        .thenReturn(
            new PageImpl<>(
                List.of(depositOnGranted, withdrawalOnGranted, depositOnOther, decidedDeposit)));

    Page<BankBookingRequestDto> result =
        service.listQueue(
            Set.of(BankBookingRequestStatus.PENDING, BankBookingRequestStatus.CONFIRMED),
            PageRequest.of(0, 20));

    assertThat(result.getContent())
        .extracting(BankBookingRequestDto::callerMayConfirm)
        .containsExactly(true, false, false, false);
  }

  /** REQ-BANK-022: the caller's page reads strictly the caller's sub, in the given states. */
  @Test
  void pageForCurrentRequester_readsTheCallersOwnRequestsInTheStates() {
    UUID caller = UUID.randomUUID();
    Set<BankBookingRequestStatus> statuses =
        Set.of(BankBookingRequestStatus.CONFIRMED, BankBookingRequestStatus.REJECTED);
    BankBookingRequest request =
        pending(
            UUID.randomUUID(),
            account(UUID.randomUUID()),
            BankBookingRequestType.DEPOSIT,
            caller,
            0L);
    request.setStatus(BankBookingRequestStatus.CONFIRMED);
    Pageable pageable = PageRequest.of(0, 3);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(caller));
    when(requestRepository.findByRequestedByAndStatusIn(caller, statuses, pageable))
        .thenReturn(new PageImpl<>(List.of(request), pageable, 7));

    Page<BankBookingRequestDto> result = service.pageForCurrentRequester(statuses, pageable);

    assertThat(result.getTotalElements()).isEqualTo(7);
    assertThat(result.getContent())
        .extracting(BankBookingRequestDto::status)
        .containsExactly(BankBookingRequestStatus.CONFIRMED);
  }

  /** Without a known caller there is nothing to read. */
  @Test
  void pageForCurrentRequester_withoutCallerIsEmptyAndDoesNotQuery() {
    when(authHelperService.currentUserId()).thenReturn(Optional.empty());

    Page<BankBookingRequestDto> result =
        service.pageForCurrentRequester(
            Set.of(BankBookingRequestStatus.PENDING), PageRequest.of(0, 3));

    assertThat(result.getTotalElements()).isZero();
    verify(requestRepository, never()).findByRequestedByAndStatusIn(any(), any(), any());
  }

  private static BankAccount account(UUID id) {
    BankAccount account = new BankAccount();
    account.setId(id);
    account.setAccountNo("KB-0001");
    account.setName("Staffel ALPHA");
    account.setType(BankAccountType.ORG_UNIT);
    account.setStatus(BankAccountStatus.ACTIVE);
    Squadron squadron = new Squadron();
    squadron.setId(UUID.randomUUID());
    squadron.setName("ALPHA");
    squadron.setShorthand("ALP");
    account.setOrgUnit(squadron);
    return account;
  }

  /**
   * Builds an active {@code CARTEL} account, a {@linkplain
   * BankAccountType#requiresDebitJustification() justification-mandating} type with no owning org
   * unit (REQ-BANK-045).
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

  /**
   * Makes the actor the caller of every decision in the test.
   *
   * @param actor the request's actor
   */
  private void callerIs(BankActor actor) {
    when(bankCaller.actor()).thenReturn(actor);
  }

  /**
   * A bank employee (not management) without any grant row.
   *
   * @param userId the employee's id
   * @return the actor
   */
  private static BankActor employee(UUID userId) {
    return TestBankActor.of(userId).roles(Roles.BANK_EMPLOYEE).build();
  }

  /**
   * A bank employee (not management) whose grant on the account carries only the flag of the type.
   *
   * @param userId the employee's id
   * @param accountId the granted account
   * @param type the movement kind the grant allows
   * @return the actor
   */
  private static BankActor employeeMayBook(
      UUID userId, UUID accountId, BankBookingRequestType type) {
    return TestBankActor.of(userId)
        .roles(Roles.BANK_EMPLOYEE)
        .grant(
            accountId,
            type == BankBookingRequestType.DEPOSIT,
            type == BankBookingRequestType.WITHDRAWAL,
            type == BankBookingRequestType.TRANSFER)
        .build();
  }

  /**
   * A bank employee (not management) holding a view-only grant row on the account.
   *
   * @param userId the employee's id
   * @param accountId the visible account
   * @return the actor
   */
  private static BankActor employeeSees(UUID userId, UUID accountId) {
    return TestBankActor.of(userId)
        .roles(Roles.BANK_EMPLOYEE)
        .grant(accountId, false, false, false)
        .build();
  }

  /**
   * REQ-BANK-055: a withdrawal request stores the Empfaenger the requester named, snapshotting the
   * handle and org-unit name at request time so a confirmation months later can stamp an
   * attributable ledger row even if the user has been deleted since.
   */
  @Test
  void create_withdrawalWithCounterparty_snapshotsHandleAndOrgUnit() {
    UUID accountId = UUID.randomUUID();
    UUID requesterSub = UUID.randomUUID();
    UUID recipientId = UUID.randomUUID();
    UUID orgUnitId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    User requester = new User();
    requester.setUsername("officer");
    when(userRepository.findById(requesterSub)).thenReturn(Optional.of(requester));
    User recipient = new User();
    recipient.setId(recipientId);
    recipient.setUsername("payee");
    when(userRepository.findById(recipientId)).thenReturn(Optional.of(recipient));
    when(orgUnitMembershipQueryService.listDirectMembershipOptions(recipientId))
        .thenReturn(
            List.of(
                new OrgUnitMembershipOptionDto(
                    orgUnitId, "ALPHA", "ALP", OrgUnitKind.SQUADRON, true)));
    when(requestRepository.save(any(BankBookingRequest.class)))
        .thenAnswer(invocation -> {
          BankBookingRequest saved = invocation.getArgument(0);
          saved.setId(UUID.randomUUID());
          return saved;
        });

    BankBookingRequestDto dto =
        service.create(
            new BankBookingRequestDraft(
                TestBankActor.of(requesterSub).build(),
                accountId,
                BankBookingRequestType.WITHDRAWAL,
                new BigDecimal("500"),
                null,
                "reason",
                null,
                BankAdmission.Admitted.UNROUTED,
                false,
                null,
                recipientId,
                orgUnitId,
                null,
                null));

    assertThat(dto.counterpartyUserId()).isEqualTo(recipientId);
    assertThat(dto.counterpartyHandle()).isEqualTo("payee");
    assertThat(dto.counterpartyOrgUnitId()).isEqualTo(orgUnitId);
    assertThat(dto.counterpartyOrgUnitName()).isEqualTo("ALPHA");
  }

  /**
   * REQ-BANK-055: the chosen org unit must be one the NAMED user actually belongs to. Without this
   * a requester could stamp an arbitrary unit onto the ledger snapshot; the bank employee's booking
   * path enforces the identical rule (REQ-BANK-044).
   */
  @Test
  void create_counterpartyOrgUnitNotAMembership_isRejected() {
    UUID accountId = UUID.randomUUID();
    UUID recipientId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    User recipient = new User();
    recipient.setUsername("payee");
    when(userRepository.findById(recipientId)).thenReturn(Optional.of(recipient));
    when(orgUnitMembershipQueryService.listDirectMembershipOptions(recipientId))
        .thenReturn(List.of());

    assertThrows(
        BadRequestException.class,
        () ->
            service.create(
                new BankBookingRequestDraft(
                    BankActor.NOBODY,
                    accountId,
                    BankBookingRequestType.WITHDRAWAL,
                    new BigDecimal("500"),
                    null,
                    "reason",
                    null,
                    BankAdmission.Admitted.UNROUTED,
                    false,
                    null,
                    recipientId,
                    UUID.randomUUID(),
                    null,
                    null)));
    verify(requestRepository, never()).save(any(BankBookingRequest.class));
  }

  /**
   * REQ-BANK-042/-055: a DEPOSIT request records no Empfaenger (the requester IS the depositor), so
   * naming one is a client error rather than a silently dropped field. Same for a TRANSFER, whose
   * counter-account already names the other side (REQ-BANK-044).
   */
  @Test
  void create_counterpartyOnDeposit_isRejected() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

    assertThrows(
        BadRequestException.class,
        () ->
            service.create(
                new BankBookingRequestDraft(
                    BankActor.NOBODY,
                    accountId,
                    BankBookingRequestType.DEPOSIT,
                    new BigDecimal("500"),
                    null,
                    null,
                    null,
                    BankAdmission.Admitted.UNROUTED,
                    false,
                    null,
                    UUID.randomUUID(),
                    null,
                    null,
                    null)));
    verify(requestRepository, never()).save(any(BankBookingRequest.class));
  }

  @Test
  void create_persistsPendingAuditsAndPublishesEvent() {
    UUID accountId = UUID.randomUUID();
    UUID requesterSub = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    User user = new User();
    user.setUsername("officer");
    when(userRepository.findById(requesterSub)).thenReturn(Optional.of(user));
    when(requestRepository.save(any(BankBookingRequest.class)))
        .thenAnswer(invocation -> {
          BankBookingRequest saved = invocation.getArgument(0);
          saved.setId(UUID.randomUUID());
          return saved;
        });

    BankBookingRequestDto dto =
        service.create(
            new BankBookingRequestDraft(
                TestBankActor.of(requesterSub).build(),
                accountId,
                BankBookingRequestType.DEPOSIT,
                new BigDecimal("500"),
                "from sale",
                null,
                null,
                BankAdmission.Admitted.UNROUTED,
                false,
                null,
                null,
                null,
                null,
                null));

    assertThat(dto.status()).isEqualTo(BankBookingRequestStatus.PENDING);
    assertThat(dto.type()).isEqualTo(BankBookingRequestType.DEPOSIT);
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.BOOKING_REQUEST_CREATED),
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
    verifyNoInteractions(authHelperService);
  }

  @Test
  void create_splitDeposit_snapshotsSplitOnRequest() {
    UUID accountId = UUID.randomUUID();
    UUID requesterSub = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(userRepository.findById(requesterSub)).thenReturn(Optional.of(new User()));
    ArgumentCaptor<BankBookingRequest> saved = ArgumentCaptor.forClass(BankBookingRequest.class);
    when(requestRepository.save(saved.capture()))
        .thenAnswer(invocation -> {
          BankBookingRequest r = invocation.getArgument(0);
          r.setId(UUID.randomUUID());
          return r;
        });

    service.create(
        new BankBookingRequestDraft(
            TestBankActor.of(requesterSub).build(),
            accountId,
            BankBookingRequestType.DEPOSIT,
            new BigDecimal("1000"),
            "from sale",
            null,
            null,
            BankAdmission.Admitted.UNROUTED,
            true,
            new BigDecimal("30"),
            null,
            null,
            null,
            null));

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
                    new BankBookingRequestDraft(
                        BankActor.NOBODY,
                        accountId,
                        BankBookingRequestType.DEPOSIT,
                        new BigDecimal("500"),
                        null,
                        null,
                        null,
                        BankAdmission.Admitted.UNROUTED,
                        false,
                        null,
                        null,
                        null,
                        null,
                        null)));
    assertThat(ex.getCode()).isEqualTo(BankConflictException.CODE_BANK_ACCOUNT_CLOSED);
    verify(requestRepository, never()).save(any());
  }

  @Test
  void create_withdrawalFromMandatingAccount_blankJustification_rejected() {
    UUID accountId = UUID.randomUUID();
    BankAccount account = cartelAccount(accountId);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () ->
                service.create(
                    new BankBookingRequestDraft(
                        BankActor.NOBODY,
                        accountId,
                        BankBookingRequestType.WITHDRAWAL,
                        new BigDecimal("500"),
                        null,
                        "   ",
                        null,
                        new BankAdmission.Admitted(
                            true, null, BankRequestApprover.RESPONSIBLE_HOLDER),
                        false,
                        null,
                        null,
                        null,
                        null,
                        null)));
    assertThat(ex.getCode()).isEqualTo(BankConflictException.CODE_BANK_JUSTIFICATION_REQUIRED);
    verify(requestRepository, never()).save(any());
  }

  @Test
  void create_withdrawalFromOrgUnitAccount_blankJustification_succeeds() {
    UUID accountId = UUID.randomUUID();
    UUID requesterSub = UUID.randomUUID();
    BankAccount account = account(accountId);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(userRepository.findById(requesterSub)).thenReturn(Optional.of(new User()));
    ArgumentCaptor<BankBookingRequest> saved = ArgumentCaptor.forClass(BankBookingRequest.class);
    when(requestRepository.save(saved.capture()))
        .thenAnswer(invocation -> {
          BankBookingRequest r = invocation.getArgument(0);
          r.setId(UUID.randomUUID());
          return r;
        });

    service.create(
        new BankBookingRequestDraft(
            TestBankActor.of(requesterSub).build(),
            accountId,
            BankBookingRequestType.WITHDRAWAL,
            new BigDecimal("500"),
            null,
            null,
            null,
            new BankAdmission.Admitted(true, null, BankRequestApprover.RESPONSIBLE_HOLDER),
            false,
            null,
            null,
            null,
            null,
            null));

    assertThat(saved.getValue().getJustification()).isNull();
  }

  @Test
  void create_withdrawalFromMandatingAccount_persistsJustification() {
    UUID accountId = UUID.randomUUID();
    UUID requesterSub = UUID.randomUUID();
    BankAccount account = cartelAccount(accountId);
    when(accountRepository.findById(accountId)).thenReturn(Optional.of(account));
    when(userRepository.findById(requesterSub)).thenReturn(Optional.of(new User()));
    ArgumentCaptor<BankBookingRequest> saved = ArgumentCaptor.forClass(BankBookingRequest.class);
    when(requestRepository.save(saved.capture()))
        .thenAnswer(invocation -> {
          BankBookingRequest r = invocation.getArgument(0);
          r.setId(UUID.randomUUID());
          return r;
        });

    service.create(
        new BankBookingRequestDraft(
            TestBankActor.of(requesterSub).build(),
            accountId,
            BankBookingRequestType.WITHDRAWAL,
            new BigDecimal("500"),
            null,
            "Reparaturkosten",
            null,
            new BankAdmission.Admitted(true, null, BankRequestApprover.RESPONSIBLE_HOLDER),
            false,
            null,
            null,
            null,
            null,
            null));

    assertThat(saved.getValue().getJustification()).isEqualTo("Reparaturkosten");
  }

  @Test
  void cancelOwn_byRequester_flipsCancelled() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    BankBookingRequest request =
        pending(requestId, account(accountId), BankBookingRequestType.DEPOSIT, requester, 0L);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    BankActor actor = TestBankActor.of(requester).build();

    BankBookingRequestDto dto = service.cancelOwn(actor, requestId, 0L);

    assertThat(dto.status()).isEqualTo(BankBookingRequestStatus.CANCELLED);
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.BOOKING_REQUEST_CANCELLED),
            any(),
            eq(null),
            eq(requester),
            any());
    ArgumentCaptor<BankBookingRequestCancelledEvent> event =
        ArgumentCaptor.forClass(BankBookingRequestCancelledEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertThat(event.getValue().requestId()).isEqualTo(requestId);
    assertThat(event.getValue().accountId()).isEqualTo(accountId);
    assertThat(event.getValue().actorSub()).isEqualTo(requester);
    assertThat(event.getValue().resolvesNotificationTypes())
        .containsExactlyInAnyOrder(
            NotificationType.BANK_BOOKING_REQUEST_CREATED,
            NotificationType.BANK_BOOKING_REQUEST_UPDATED,
            NotificationType.BANK_BOOKING_REQUEST_APPROVAL_NEEDED,
            NotificationType.BANK_BOOKING_REQUEST_APPROVED);
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
    BankActor actor = TestBankActor.of(UUID.randomUUID()).build();

    assertThrows(NotFoundException.class, () -> service.cancelOwn(actor, requestId, 0L));
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
    BankActor actor = TestBankActor.of(requester).build();

    BankConflictException ex =
        assertThrows(BankConflictException.class, () -> service.cancelOwn(actor, requestId, 1L));
    assertThat(ex.getCode()).isEqualTo(BankConflictException.CODE_BANK_REQUEST_NOT_PENDING);
  }

  /**
   * REQ-BANK-056: the requester corrects amount, Notiz and Begruendung on their own pending
   * request; the re-derived approval snapshot the seam computed is stamped, and the edit is
   * audited.
   */
  @Test
  void updateOwn_byRequester_appliesTheCorrectionAndAudits() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    BankBookingRequest request =
        pending(requestId, account(accountId), BankBookingRequestType.WITHDRAWAL, requester, 0L);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    BankActor actor = TestBankActor.of(requester).build();

    service.updateOwn(
        actor,
        requestId,
        new UpdateBankBookingRequest(
            new BigDecimal("900"), "corrected note", "corrected reason", null, null, null, 0L),
        true,
        new BigDecimal("500"),
        BankRequestApprover.RESPONSIBLE_HOLDER);

    assertThat(request.getAmount()).isEqualByComparingTo(new BigDecimal("900"));
    assertThat(request.getNote()).isEqualTo("corrected note");
    assertThat(request.getJustification()).isEqualTo("corrected reason");
    assertThat(request.getStatus()).isEqualTo(BankBookingRequestStatus.PENDING);
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.BOOKING_REQUEST_UPDATED),
            eq(accountId),
            eq(null),
            eq(requester),
            any());
    ArgumentCaptor<BankBookingRequestUpdatedEvent> event =
        ArgumentCaptor.forClass(BankBookingRequestUpdatedEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertThat(event.getValue().requestId()).isEqualTo(requestId);
    assertThat(event.getValue().accountId()).isEqualTo(accountId);
    assertThat(event.getValue().amount()).isEqualByComparingTo(new BigDecimal("900"));
    assertThat(event.getValue().actorSub()).isEqualTo(requester);
    assertThat(event.getValue().eventType())
        .isEqualTo(NotificationEventType.BANK_BOOKING_REQUEST_UPDATED_BY_REQUESTER);
    assertThat(event.getValue().resolvesNotificationTypes())
        .containsExactlyInAnyOrder(
            NotificationType.BANK_BOOKING_REQUEST_CREATED,
            NotificationType.BANK_BOOKING_REQUEST_UPDATED,
            NotificationType.BANK_BOOKING_REQUEST_APPROVAL_NEEDED);
    assertThat(event.getValue().renderParams()).containsEntry("amount", "900");
  }

  /**
   * REQ-BANK-056, the security-critical half: raising the amount past the requester's limit must
   * re-arm the approval gate. The seam re-derives the snapshot and this method stamps it, so an
   * edit cannot ride the original below-limit snapshot into a confirmation without approval.
   */
  @Test
  void updateOwn_raisingTheAmount_reArmsTheApprovalGate() {
    UUID requestId = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            requestId,
            account(UUID.randomUUID()),
            BankBookingRequestType.WITHDRAWAL,
            requester,
            0L);
    request.setRequiresOwnerApproval(false);
    request.setApplicableLimit(new BigDecimal("1000"));
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    BankActor actor = TestBankActor.of(requester).build();

    service.updateOwn(
        actor,
        requestId,
        new UpdateBankBookingRequest(new BigDecimal("5000"), null, "reason", null, null, null, 0L),
        true,
        new BigDecimal("1000"),
        BankRequestApprover.RESPONSIBLE_HOLDER);

    assertThat(request.isRequiresOwnerApproval()).isTrue();
    assertThat(request.getRequiredApprover()).isEqualTo(BankRequestApprover.RESPONSIBLE_HOLDER);
  }

  /** A request whose over-limit approval was already granted cannot be edited (REQ-BANK-056). */
  @Test
  void updateOwn_alreadyApproved_throwsConflict() {
    UUID requestId = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            requestId,
            account(UUID.randomUUID()),
            BankBookingRequestType.WITHDRAWAL,
            requester,
            0L);
    request.setRequiresOwnerApproval(true);
    request.setOwnerApprovalGranted(true);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    BankActor actor = TestBankActor.of(requester).build();

    BankConflictException conflict =
        assertThrows(
            BankConflictException.class,
            () ->
                service.updateOwn(
                    actor,
                    requestId,
                    new UpdateBankBookingRequest(
                        new BigDecimal("999999"), null, "reason", null, null, null, 0L),
                    true,
                    null,
                    BankRequestApprover.RESPONSIBLE_HOLDER));

    assertThat(conflict.getCode())
        .isEqualTo(BankConflictException.CODE_BANK_REQUEST_ALREADY_APPROVED);
    assertThat(request.getAmount()).isEqualByComparingTo(new BigDecimal("500"));
  }

  /** REQ-BANK-056: a foreign request is reported as not found, never as forbidden. */
  @Test
  void updateOwn_foreignRequest_throwsNotFound() {
    UUID requestId = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            requestId,
            account(UUID.randomUUID()),
            BankBookingRequestType.WITHDRAWAL,
            UUID.randomUUID(),
            0L);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    BankActor actor = TestBankActor.of(UUID.randomUUID()).build();

    assertThrows(
        NotFoundException.class,
        () ->
            service.updateOwn(
                actor,
                requestId,
                new UpdateBankBookingRequest(
                    new BigDecimal("900"), null, "reason", null, null, null, 0L),
                false,
                null,
                null));
  }

  /** REQ-BANK-056: a request that is no longer PENDING cannot be edited. */
  @Test
  void updateOwn_alreadyDecided_throwsConflict() {
    UUID requestId = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            requestId,
            account(UUID.randomUUID()),
            BankBookingRequestType.WITHDRAWAL,
            requester,
            0L);
    request.setStatus(BankBookingRequestStatus.CONFIRMED);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    BankActor actor = TestBankActor.of(requester).build();

    BankConflictException conflict =
        assertThrows(
            BankConflictException.class,
            () ->
                service.updateOwn(
                    actor,
                    requestId,
                    new UpdateBankBookingRequest(
                        new BigDecimal("900"), null, "reason", null, null, null, 0L),
                    false,
                    null,
                    null));

    assertThat(conflict.getCode()).isEqualTo(BankConflictException.CODE_BANK_REQUEST_NOT_PENDING);
  }

  /** REQ-BANK-056: a stale echoed version is the usual optimistic-lock 409. */
  @Test
  void updateOwn_versionMismatch_throwsOptimisticLock() {
    UUID requestId = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            requestId,
            account(UUID.randomUUID()),
            BankBookingRequestType.WITHDRAWAL,
            requester,
            3L);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    BankActor actor = TestBankActor.of(requester).build();

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () ->
            service.updateOwn(
                actor,
                requestId,
                new UpdateBankBookingRequest(
                    new BigDecimal("900"), null, "reason", null, null, null, 1L),
                false,
                null,
                null));
  }

  /**
   * REQ-BANK-045 still binds an edit: a mandating source account (CARTEL) rejects a correction that
   * blanks the Begruendung, so an edit cannot launder away a mandatory reason.
   */
  @Test
  void updateOwn_blankingAMandatoryJustification_isRejected() {
    UUID requestId = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            requestId,
            cartelAccount(UUID.randomUUID()),
            BankBookingRequestType.WITHDRAWAL,
            requester,
            0L);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    BankActor actor = TestBankActor.of(requester).build();

    assertThrows(
        BankConflictException.class,
        () ->
            service.updateOwn(
                actor,
                requestId,
                new UpdateBankBookingRequest(
                    new BigDecimal("900"), null, null, null, null, null, 0L),
                false,
                null,
                null));
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
    callerIs(employeeMayBook(decider, accountId, BankBookingRequestType.DEPOSIT));
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
    UUID requesterOrgUnit = UUID.randomUUID();
    when(orgUnitMembershipQueryService.findPrimaryDirectMembershipOrgUnitId(requester))
        .thenReturn(Optional.of(requesterOrgUnit));

    BankBookingRequestDto dto = service.confirm(requestId, holderId, null, false, null, 0L);

    assertThat(dto.status()).isEqualTo(BankBookingRequestStatus.CONFIRMED);
    assertThat(dto.holderId()).isEqualTo(holderId);
    ArgumentCaptor<BankDepositRequest> booked = ArgumentCaptor.forClass(BankDepositRequest.class);
    verify(bankLedgerService).bookDeposit(booked.capture());
    assertThat(booked.getValue().accountId()).isEqualTo(accountId);
    assertThat(booked.getValue().holderId()).isEqualTo(holderId);
    assertThat(booked.getValue().counterpartyUserId()).isEqualTo(requester);
    assertThat(booked.getValue().counterpartyOrgUnitId()).isEqualTo(requesterOrgUnit);
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.BOOKING_REQUEST_CONFIRMED),
            eq(accountId),
            eq(txId),
            eq(requester),
            any());
    ArgumentCaptor<BankBookingRequestConfirmedEvent> event =
        ArgumentCaptor.forClass(BankBookingRequestConfirmedEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertThat(booked.getValue().splitEnabled()).isFalse();
    assertThat(event.getValue().contextRecipientUserId()).isEqualTo(requester);
    assertThat(event.getValue().actorSub()).isEqualTo(decider);
    assertThat(event.getValue().contextAccountId()).isEqualTo(accountId);
    assertThat(event.getValue().resolvesNotificationTypes())
        .containsExactlyInAnyOrder(
            NotificationType.BANK_BOOKING_REQUEST_CREATED,
            NotificationType.BANK_BOOKING_REQUEST_UPDATED,
            NotificationType.BANK_BOOKING_REQUEST_APPROVAL_NEEDED,
            NotificationType.BANK_BOOKING_REQUEST_APPROVED);
  }

  @Test
  void confirm_staffNote_isSnapshottedOnRequestAndHandedToTheBooking() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID holderId = UUID.randomUUID();
    UUID txId = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    UUID decider = UUID.randomUUID();
    BankBookingRequest request =
        pending(requestId, account(accountId), BankBookingRequestType.DEPOSIT, requester, 0L);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    callerIs(employeeMayBook(decider, accountId, BankBookingRequestType.DEPOSIT));
    when(bankLedgerService.bookDeposit(any(BankDepositRequest.class)))
        .thenReturn(
            new BankTransactionDto(txId, BankTransactionType.DEPOSIT, "from sale", Instant.now()));
    BankHolder holder = new BankHolder();
    holder.setId(holderId);
    holder.setHandle("greluc");
    holder.setActive(true);
    when(holderRepository.findById(holderId)).thenReturn(Optional.of(holder));
    when(transactionRepository.findById(txId)).thenReturn(Optional.of(new BankTransaction()));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(decider));

    service.confirm(requestId, holderId, null, false, "Bar uebergeben, Zeuge: greluc", 0L);

    ArgumentCaptor<BankDepositRequest> booked = ArgumentCaptor.forClass(BankDepositRequest.class);
    verify(bankLedgerService).bookDeposit(booked.capture());
    assertThat(booked.getValue().staffNote()).isEqualTo("Bar uebergeben, Zeuge: greluc");
    assertThat(request.getStaffNote()).isEqualTo("Bar uebergeben, Zeuge: greluc");
  }

  /**
   * REQ-BANK-055: an Empfaenger the requester NAMED wins over the requester-derived counterparty,
   * and the org unit they chose is carried onto the booking. Without this the payout would still be
   * attributed to whoever filed the request.
   */
  @Test
  void confirm_withdrawal_namedCounterpartyWinsOverTheRequester() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID holderId = UUID.randomUUID();
    UUID txId = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    UUID recipientId = UUID.randomUUID();
    UUID orgUnitId = UUID.randomUUID();
    BankBookingRequest request =
        pending(requestId, account(accountId), BankBookingRequestType.WITHDRAWAL, requester, 0L);
    request.setCounterpartyUserId(recipientId);
    request.setCounterpartyHandle("payee");
    request.setCounterpartyOrgUnitId(orgUnitId);
    request.setCounterpartyOrgUnitName("ALPHA");
    stubWithdrawalConfirm(requestId, accountId, holderId, txId, request);
    when(orgUnitMembershipQueryService.listDirectMembershipOptions(recipientId))
        .thenReturn(
            List.of(
                new OrgUnitMembershipOptionDto(
                    orgUnitId, "ALPHA", "ALP", OrgUnitKind.SQUADRON, true)));

    service.confirm(requestId, holderId, null, false, null, 0L);

    ArgumentCaptor<BankWithdrawalRequest> booked =
        ArgumentCaptor.forClass(BankWithdrawalRequest.class);
    verify(bankLedgerService).bookWithdrawal(booked.capture());
    assertThat(booked.getValue().counterpartyUserId()).isEqualTo(recipientId);
    assertThat(booked.getValue().counterpartyOrgUnitId()).isEqualTo(orgUnitId);
  }

  /**
   * On confirming a withdrawal, a counterparty org unit that is no longer current degrades to the
   * counterparty's primary unit (REQ-BANK-055).
   */
  @Test
  void confirm_withdrawal_staleCounterpartyOrgUnitDegradesToThePrimary() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID holderId = UUID.randomUUID();
    UUID txId = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    UUID recipientId = UUID.randomUUID();
    UUID leftUnit = UUID.randomUUID();
    UUID primaryUnit = UUID.randomUUID();
    BankBookingRequest request =
        pending(requestId, account(accountId), BankBookingRequestType.WITHDRAWAL, requester, 0L);
    request.setCounterpartyUserId(recipientId);
    request.setCounterpartyHandle("payee");
    request.setCounterpartyOrgUnitId(leftUnit);
    request.setCounterpartyOrgUnitName("Ex-Staffel");
    stubWithdrawalConfirm(requestId, accountId, holderId, txId, request);
    when(orgUnitMembershipQueryService.listDirectMembershipOptions(recipientId))
        .thenReturn(List.of());
    when(orgUnitMembershipQueryService.findPrimaryDirectMembershipOrgUnitId(recipientId))
        .thenReturn(Optional.of(primaryUnit));

    service.confirm(requestId, holderId, null, false, null, 0L);

    ArgumentCaptor<BankWithdrawalRequest> booked =
        ArgumentCaptor.forClass(BankWithdrawalRequest.class);
    verify(bankLedgerService).bookWithdrawal(booked.capture());
    assertThat(booked.getValue().counterpartyUserId()).isEqualTo(recipientId);
    assertThat(booked.getValue().counterpartyOrgUnitId()).isEqualTo(primaryUnit);
  }

  /**
   * A withdrawal request naming no counterparty derives the requester as the Empfaenger on
   * confirmation (REQ-BANK-044).
   */
  @Test
  void confirm_withdrawal_withoutNamedCounterparty_stillDerivesTheRequester() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID holderId = UUID.randomUUID();
    UUID txId = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    UUID primaryUnit = UUID.randomUUID();
    BankBookingRequest request =
        pending(requestId, account(accountId), BankBookingRequestType.WITHDRAWAL, requester, 0L);
    stubWithdrawalConfirm(requestId, accountId, holderId, txId, request);
    when(orgUnitMembershipQueryService.findPrimaryDirectMembershipOrgUnitId(requester))
        .thenReturn(Optional.of(primaryUnit));

    service.confirm(requestId, holderId, null, false, null, 0L);

    ArgumentCaptor<BankWithdrawalRequest> booked =
        ArgumentCaptor.forClass(BankWithdrawalRequest.class);
    verify(bankLedgerService).bookWithdrawal(booked.capture());
    assertThat(booked.getValue().counterpartyUserId()).isEqualTo(requester);
    assertThat(booked.getValue().counterpartyOrgUnitId()).isEqualTo(primaryUnit);
  }

  /**
   * Shared stubs for confirming a WITHDRAWAL request: the locked row, an employee whose grant
   * carries the withdrawal flag, an active holder, the booked transaction and the acting user.
   *
   * @param requestId the request being confirmed
   * @param accountId the source account
   * @param holderId the holder the employee records
   * @param txId the id of the transaction the ledger returns
   * @param request the locked request row
   */
  private void stubWithdrawalConfirm(
      UUID requestId, UUID accountId, UUID holderId, UUID txId, BankBookingRequest request) {
    UUID decider = UUID.randomUUID();
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    callerIs(employeeMayBook(decider, accountId, BankBookingRequestType.WITHDRAWAL));
    when(bankLedgerService.bookWithdrawal(any(BankWithdrawalRequest.class)))
        .thenReturn(
            new BankTransactionDto(txId, BankTransactionType.WITHDRAWAL, "payout", Instant.now()));
    BankHolder holder = new BankHolder();
    holder.setId(holderId);
    holder.setHandle("greluc");
    holder.setActive(true);
    when(holderRepository.findById(holderId)).thenReturn(Optional.of(holder));
    when(transactionRepository.findById(txId)).thenReturn(Optional.of(new BankTransaction()));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(decider));
  }

  @Test
  void confirm_splitDeposit_booksWithSplitSnapshot() {
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
    callerIs(employeeMayBook(decider, accountId, BankBookingRequestType.DEPOSIT));
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

    service.confirm(requestId, holderId, null, false, null, 0L);

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
    callerIs(employeeMayBook(UUID.randomUUID(), accountId, BankBookingRequestType.DEPOSIT));

    assertThrows(
        AccessDeniedException.class,
        () -> service.confirm(requestId, UUID.randomUUID(), null, false, null, 0L));
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
            () -> service.confirm(requestId, UUID.randomUUID(), null, false, null, 2L));
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
        () -> service.confirm(requestId, UUID.randomUUID(), null, false, null, 4L));
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
    callerIs(employeeSees(decider, accountId));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(decider));
    when(userRepository.findById(decider)).thenReturn(Optional.of(new User()));

    BankBookingRequestDto dto = service.reject(requestId, "duplicate", 0L);

    assertThat(dto.status()).isEqualTo(BankBookingRequestStatus.REJECTED);
    assertThat(dto.rejectReason()).isEqualTo("duplicate");
    verify(bankLedgerService, never()).bookDeposit(any());
    ArgumentCaptor<BankBookingRequestRejectedEvent> event =
        ArgumentCaptor.forClass(BankBookingRequestRejectedEvent.class);
    verify(eventPublisher).publishEvent(event.capture());
    assertThat(event.getValue().reason()).isEqualTo("duplicate");
    assertThat(event.getValue().contextAccountId()).isEqualTo(accountId);
    assertThat(event.getValue().resolvesNotificationTypes())
        .containsExactlyInAnyOrder(
            NotificationType.BANK_BOOKING_REQUEST_CREATED,
            NotificationType.BANK_BOOKING_REQUEST_UPDATED,
            NotificationType.BANK_BOOKING_REQUEST_APPROVAL_NEEDED,
            NotificationType.BANK_BOOKING_REQUEST_APPROVED);
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
    callerIs(employee(UUID.randomUUID()));

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () -> service.confirm(requestId, UUID.randomUUID(), null, false, null, 0L));
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
    stubBookableDeposit(request, accountId, holderId, txId);

    BankBookingRequestDto dto =
        service.confirm(requestId, holderId, null, true, "Freigabe: greluc", 0L);

    assertThat(dto.status()).isEqualTo(BankBookingRequestStatus.CONFIRMED);
    verify(bankAuditService)
        .record(
            eq(BankAuditEventType.BOOKING_REQUEST_OWNER_APPROVAL_CONFIRMED),
            eq(accountId),
            eq(txId),
            eq(requester),
            eq("owner approval confirmed for request " + requestId.toString().substring(0, 8)));
  }

  @ParameterizedTest
  @NullSource
  @ValueSource(strings = {"", "   "})
  void confirm_attestedOverLimitWithoutApproverNote_isRefusedAndBooksNothing(String staffNote) {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            requestId, account(accountId), BankBookingRequestType.DEPOSIT, UUID.randomUUID(), 0L);
    request.setRequiresOwnerApproval(true);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    callerIs(employee(UUID.randomUUID()));

    BankApproverNoteRequiredException ex =
        assertThrows(
            BankApproverNoteRequiredException.class,
            () -> service.confirm(requestId, UUID.randomUUID(), null, true, staffNote, 0L));

    assertThat(ex.code()).isEqualTo("BANK_APPROVER_NOTE_REQUIRED");
    assertThat(ex.status()).isEqualTo(HttpStatus.BAD_REQUEST);
    verify(bankLedgerService, never()).bookDeposit(any());
    assertThat(request.getStatus()).isEqualTo(BankBookingRequestStatus.PENDING);
  }

  @Test
  void confirm_overLimitWithRecordedApproval_booksWithoutApproverNote() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID holderId = UUID.randomUUID();
    UUID txId = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            requestId, account(accountId), BankBookingRequestType.DEPOSIT, UUID.randomUUID(), 0L);
    request.setRequiresOwnerApproval(true);
    request.setOwnerApprovalGranted(true);
    stubBookableDeposit(request, accountId, holderId, txId);

    BankBookingRequestDto dto = service.confirm(requestId, holderId, null, true, null, 0L);

    assertThat(dto.status()).isEqualTo(BankBookingRequestStatus.CONFIRMED);
    assertThat(dto.staffNote()).isNull();
  }

  /**
   * Stubs every collaborator a deposit confirm reaches, so the confirm books {@code txId} onto
   * {@code holderId}.
   *
   * @param request the pending request the repository returns under lock
   * @param accountId the request's account, on which the caller may confirm
   * @param holderId the holder the confirm records
   * @param txId the transaction the ledger books
   */
  private void stubBookableDeposit(
      BankBookingRequest request, UUID accountId, UUID holderId, UUID txId) {
    UUID decider = UUID.randomUUID();
    when(requestRepository.findByIdForUpdate(request.getId())).thenReturn(Optional.of(request));
    callerIs(employeeMayBook(decider, accountId, BankBookingRequestType.DEPOSIT));
    when(bankLedgerService.bookDeposit(any(BankDepositRequest.class)))
        .thenReturn(new BankTransactionDto(txId, BankTransactionType.DEPOSIT, null, Instant.now()));
    BankHolder holder = new BankHolder();
    holder.setId(holderId);
    holder.setHandle("greluc");
    holder.setActive(true);
    when(holderRepository.findById(holderId)).thenReturn(Optional.of(holder));
    when(transactionRepository.findById(txId)).thenReturn(Optional.of(new BankTransaction()));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(decider));
    when(userRepository.findById(any())).thenReturn(Optional.of(new User()));
  }

  @Test
  void confirm_byTheRequester_isRefusedAsFourEyes() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    BankBookingRequest request =
        pending(requestId, account(accountId), BankBookingRequestType.DEPOSIT, requester, 0L);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    callerIs(employeeMayBook(requester, accountId, BankBookingRequestType.DEPOSIT));

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () -> service.confirm(requestId, UUID.randomUUID(), null, false, null, 0L));

    assertThat(ex.getCode()).isEqualTo(BankConflictException.CODE_BANK_FOUR_EYES_REQUIRED);
    verify(bankLedgerService, never()).bookDeposit(any());
    assertThat(request.getStatus()).isEqualTo(BankBookingRequestStatus.PENDING);
  }

  @Test
  void confirm_byTheRequesterWhoIsAdmin_booksAndConfirms() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID holderId = UUID.randomUUID();
    UUID txId = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    BankBookingRequest request =
        pending(requestId, account(accountId), BankBookingRequestType.DEPOSIT, requester, 0L);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    callerIs(TestBankActor.of(requester).admin().build());
    when(authHelperService.currentUserId()).thenReturn(Optional.of(requester));
    when(bankLedgerService.bookDeposit(any(BankDepositRequest.class)))
        .thenReturn(new BankTransactionDto(txId, BankTransactionType.DEPOSIT, null, Instant.now()));
    BankHolder holder = new BankHolder();
    holder.setId(holderId);
    holder.setHandle("greluc");
    holder.setActive(true);
    when(holderRepository.findById(holderId)).thenReturn(Optional.of(holder));
    when(transactionRepository.findById(txId)).thenReturn(Optional.of(new BankTransaction()));
    when(userRepository.findById(any())).thenReturn(Optional.of(new User()));

    BankBookingRequestDto dto = service.confirm(requestId, holderId, null, false, null, 0L);

    assertThat(dto.status()).isEqualTo(BankBookingRequestStatus.CONFIRMED);
  }

  @Test
  void reject_byTheRequester_isAllowed() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID requester = UUID.randomUUID();
    BankBookingRequest request =
        pending(requestId, account(accountId), BankBookingRequestType.DEPOSIT, requester, 0L);
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    callerIs(employeeSees(requester, accountId));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(requester));
    when(userRepository.findById(requester)).thenReturn(Optional.of(new User()));

    BankBookingRequestDto dto = service.reject(requestId, "changed my mind", 0L);

    assertThat(dto.status()).isEqualTo(BankBookingRequestStatus.REJECTED);
  }

  @Test
  void applyOwnerApprovalWithinTransaction_grantByTheRequester_isRefusedAsFourEyes() {
    UUID requester = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            UUID.randomUUID(),
            account(UUID.randomUUID()),
            BankBookingRequestType.WITHDRAWAL,
            requester,
            0L);
    request.setRequiresOwnerApproval(true);
    callerIs(TestBankActor.of(requester).build());

    BankConflictException ex =
        assertThrows(
            BankConflictException.class,
            () -> service.applyOwnerApprovalWithinTransaction(request, true));

    assertThat(ex.getCode()).isEqualTo(BankConflictException.CODE_BANK_FOUR_EYES_REQUIRED);
    assertThat(request.isOwnerApprovalGranted()).isFalse();
    verify(eventPublisher, never()).publishEvent(any());
  }

  @Test
  void applyOwnerApprovalWithinTransaction_grantByTheRequesterWhoIsAdmin_isGranted() {
    UUID requester = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            UUID.randomUUID(),
            account(UUID.randomUUID()),
            BankBookingRequestType.WITHDRAWAL,
            requester,
            0L);
    request.setRequiresOwnerApproval(true);
    callerIs(TestBankActor.of(requester).admin().build());
    when(authHelperService.currentUserId()).thenReturn(Optional.of(requester));
    when(userRepository.findById(requester)).thenReturn(Optional.of(new User()));

    BankBookingRequestDto dto = service.applyOwnerApprovalWithinTransaction(request, true);

    assertThat(dto.ownerApprovalGranted()).isTrue();
  }

  @Test
  void listQueue_marksTheCallersOwnRequestAsNotConfirmable() {
    UUID caller = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankBookingRequest own =
        pending(UUID.randomUUID(), account(accountId), BankBookingRequestType.DEPOSIT, caller, 0L);
    BankBookingRequest foreign =
        pending(
            UUID.randomUUID(),
            account(accountId),
            BankBookingRequestType.DEPOSIT,
            UUID.randomUUID(),
            0L);
    Pageable pageable = PageRequest.of(0, 10);
    Set<BankBookingRequestStatus> statuses = Set.of(BankBookingRequestStatus.PENDING);
    callerIs(TestBankActor.of(caller).bankManagement().build());
    when(requestRepository.findByStatusIn(statuses, pageable))
        .thenReturn(new PageImpl<>(List.of(own, foreign), pageable, 2));

    Page<BankBookingRequestDto> page = service.listQueue(statuses, pageable);

    assertThat(page.getContent())
        .extracting(BankBookingRequestDto::callerMayConfirm)
        .containsExactly(false, true);
  }

  @Test
  void listForeign_leavesOutTheCallersOwnRequestsUnlessAdmin() {
    UUID caller = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    BankBookingRequest own =
        pending(UUID.randomUUID(), account(accountId), BankBookingRequestType.DEPOSIT, caller, 0L);
    BankBookingRequest foreign =
        pending(
            UUID.randomUUID(),
            account(accountId),
            BankBookingRequestType.DEPOSIT,
            UUID.randomUUID(),
            0L);
    when(requestRepository.findByAccountIdInOrderByCreatedAtDesc(Set.of(accountId)))
        .thenReturn(List.of(own, foreign));

    assertThat(service.listForeign(holderScope(TestBankActor.of(caller).build(), accountId)))
        .extracting(BankBookingRequestDto::id)
        .containsExactly(foreign.getId());

    assertThat(
            service.listForeign(holderScope(TestBankActor.of(caller).admin().build(), accountId)))
        .hasSize(2);
  }

  @Test
  void listForeign_withoutCandidateAccounts_isEmptyWithoutQuerying() {
    BankAccessRules.ForeignRequestScope scope =
        new BankAccessRules.ForeignRequestScope(
            policy.staffRights(TestBankActor.of(UUID.randomUUID()).build()), Set.of(), Set.of());

    assertThat(service.listForeign(scope)).isEmpty();
    verify(requestRepository, never()).findByAccountIdInOrderByCreatedAtDesc(any());
  }

  /**
   * The foreign-request scope of an actor who is the responsible holder of exactly one account.
   *
   * @param actor the actor
   * @param accountId the account the actor holds
   * @return the scope with that account as the only candidate
   */
  private BankAccessRules.ForeignRequestScope holderScope(BankActor actor, UUID accountId) {
    return new BankAccessRules.ForeignRequestScope(
        policy.staffRights(actor), Set.of(accountId), Set.of(accountId));
  }

  /**
   * REQ-BANK-011/-023: confirming a transfer needs the transfer capability on the source account
   * only; the destination needs no grant.
   */
  @Test
  void confirm_transfer_bypassesDestinationVisibilityGate() {
    UUID requestId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID destId = UUID.randomUUID();
    UUID sourceHolder = UUID.randomUUID();
    UUID destHolder = UUID.randomUUID();
    UUID txId = UUID.randomUUID();
    UUID decider = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            requestId, account(accountId), BankBookingRequestType.TRANSFER, UUID.randomUUID(), 0L);
    request.setTargetAccount(account(destId));
    when(requestRepository.findByIdForUpdate(requestId)).thenReturn(Optional.of(request));
    callerIs(employeeMayBook(decider, accountId, BankBookingRequestType.TRANSFER));
    when(bankLedgerService.bookTransfer(any(BankTransferRequest.class), anyBoolean()))
        .thenReturn(
            new BankTransactionDto(txId, BankTransactionType.TRANSFER, null, Instant.now()));
    BankHolder holder = new BankHolder();
    holder.setId(sourceHolder);
    holder.setHandle("greluc");
    holder.setActive(true);
    when(holderRepository.findById(sourceHolder)).thenReturn(Optional.of(holder));
    when(transactionRepository.findById(txId)).thenReturn(Optional.of(new BankTransaction()));
    when(authHelperService.currentUserId()).thenReturn(Optional.of(decider));
    when(userRepository.findById(any())).thenReturn(Optional.of(new User()));

    BankBookingRequestDto dto =
        service.confirm(requestId, sourceHolder, destHolder, false, null, 0L);

    assertThat(dto.status()).isEqualTo(BankBookingRequestStatus.CONFIRMED);
    ArgumentCaptor<BankTransferRequest> booked = ArgumentCaptor.forClass(BankTransferRequest.class);
    verify(bankLedgerService).bookTransfer(booked.capture(), eq(true));
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
    callerIs(TestBankActor.of(approver).build());
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
    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(published.capture());
    NoticeEvent notice = (NoticeEvent) published.getValue();
    assertThat(notice.eventType()).isEqualTo(NotificationEventType.BANK_BOOKING_REQUEST_APPROVED);
    assertThat(notice.entityId()).isEqualTo(requestId);
    assertThat(notice.contextAccountId()).isEqualTo(accountId);
    assertThat(notice.contextRecipientUserId()).isEqualTo(requester);
    assertThat(notice.actorSub()).isEqualTo(approver);
    assertThat(notice.resolvesNotificationTypes())
        .containsExactlyInAnyOrder(
            NotificationType.BANK_BOOKING_REQUEST_APPROVED,
            NotificationType.BANK_BOOKING_REQUEST_APPROVAL_NEEDED);
  }

  @Test
  void applyOwnerApprovalWithinTransaction_revoke_clearsTheReadyToConfirmNotices() {
    UUID requestId = UUID.randomUUID();
    BankBookingRequest request =
        pending(
            requestId,
            account(UUID.randomUUID()),
            BankBookingRequestType.DEPOSIT,
            UUID.randomUUID(),
            0L);
    request.setRequiresOwnerApproval(true);
    request.setOwnerApprovalGranted(true);
    when(authHelperService.currentUserId()).thenReturn(Optional.of(UUID.randomUUID()));

    service.applyOwnerApprovalWithinTransaction(request, false);

    ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
    verify(eventPublisher).publishEvent(published.capture());
    NoticeEvent notice = (NoticeEvent) published.getValue();
    assertThat(notice.eventType())
        .isEqualTo(NotificationEventType.BANK_BOOKING_REQUEST_APPROVAL_REVOKED);
    assertThat(notice.contextBankRequestId()).isEqualTo(requestId);
    assertThat(notice.resolvesNotificationTypes())
        .containsExactlyInAnyOrder(
            NotificationType.BANK_BOOKING_REQUEST_APPROVED,
            NotificationType.BANK_BOOKING_REQUEST_APPROVAL_NEEDED);
  }

  @Test
  void hasOpenRequests_delegatesToRepository() {
    UUID accountId = UUID.randomUUID();
    when(requestRepository.existsByAccountIdAndStatus(accountId, BankBookingRequestStatus.PENDING))
        .thenReturn(true);

    assertThat(service.hasOpenRequests(accountId)).isTrue();
  }
}

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

import static de.greluc.krt.profit.basetool.backend.bank.api.BankAmounts.plain;

import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.api.BankApproverNoteRequiredException;
import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankConflictException;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurpose;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurposeKind;
import de.greluc.krt.profit.basetool.backend.bank.api.BankRequestApprover;
import de.greluc.krt.profit.basetool.backend.bank.api.BankStaffRights;
import de.greluc.krt.profit.basetool.backend.bank.api.BookingRequestPurposeObserver;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestCancelledEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestConfirmedEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestCreatedEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestRejectedEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestUpdatedEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankNotices;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lifecycle engine for confirm-before-post bank booking requests (REQ-BANK-022/-023).
 *
 * <p>Org-unit-blind: it consults only bank roles and grants (REQ-BANK-008), while {@link
 * OrgUnitBankRequestService} handles requester-side authorization. A {@code PENDING} request moves
 * no money; only {@link #confirm} books it through {@link BankLedgerService}. Every decision locks
 * the request row and checks its {@code @Version}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class BankBookingRequestService {

  private final BankBookingRequestRepository requestRepository;
  private final BankAccountRepository accountRepository;
  private final BankHolderRepository holderRepository;
  private final BankTransactionRepository transactionRepository;
  private final BankLedgerService bankLedgerService;
  private final BankCaller bankCaller;
  private final BankAccessRules policy;
  private final BankAuditService bankAuditService;
  private final AuthHelperService authHelperService;
  private final UserRepository userRepository;
  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;
  private final ApplicationEventPublisher eventPublisher;
  private final ObjectProvider<BookingRequestPurposeObserver> purposeObservers;
  private final BankRequestClientNames clientNames;

  /**
   * Persists a new {@code PENDING} booking request, audits it and fires the notification event
   * (REQ-BANK-022/-026). Enforces only the bank-domain rules; the caller has already checked
   * visibility and resolved the approval snapshot.
   *
   * @param accountId the source account (already view-checked by the caller)
   * @param type deposit, withdrawal or transfer
   * @param amount the requested whole-aUEC amount
   * @param note the requester's optional note
   * @param justification the requester's optional Begr&uuml;ndung; required for a {@code
   *     WITHDRAWAL} / {@code TRANSFER} from a {@linkplain
   *     BankAccountType#requiresDebitJustification() justification-mandating} account
   *     (REQ-BANK-045)
   * @param targetAccountId the transfer destination, or {@code null} for deposit/withdrawal
   * @param requiresOwnerApproval whether the amount exceeds the requester's approval limit
   *     (snapshot)
   * @param applicableLimit the requester's resolved approval limit (snapshot), or {@code null}
   * @param requiredApprover the approver class a flagged request needs (snapshot), or {@code null}
   * @param splitEnabled whether a deposit is split across the squadron accounts on confirmation
   *     (REQ-BANK-044); always {@code false} for withdrawal/transfer
   * @param splitPercent the whole percent (1–100) to distribute; {@code null} unless {@code
   *     splitEnabled}
   * @param counterpartyUserId the Empf&auml;nger of a {@code WITHDRAWAL} request (REQ-BANK-055), or
   *     {@code null} to derive the requester at confirmation
   * @param counterpartyOrgUnitId that Empf&auml;nger's org unit, or {@code null}
   * @param purpose what another module raised the request for (REQ-BANK-061), or {@code null}
   * @param clientId the connected application that raised it through the exchange (REQ-BANK-067),
   *     or {@code null} for a request raised in the Basetool
   * @return the created request
   * @throws NotFoundException when the source or destination account does not exist
   * @throws BankConflictException with {@code BANK_ACCOUNT_CLOSED} on a closed account or {@code
   *     BANK_SELF_TRANSFER} when source equals destination
   * @throws BadRequestException when a counterparty is named on a non-withdrawal, or its org unit
   *     is not one of that user's memberships
   */
  @NotNull
  @Transactional
  public BankBookingRequestDto create(
      @NotNull UUID accountId,
      @NotNull BankBookingRequestType type,
      @NotNull BigDecimal amount,
      @Nullable String note,
      @Nullable String justification,
      @Nullable UUID targetAccountId,
      boolean requiresOwnerApproval,
      @Nullable BigDecimal applicableLimit,
      @Nullable BankRequestApprover requiredApprover,
      boolean splitEnabled,
      @Nullable BigDecimal splitPercent,
      @Nullable UUID counterpartyUserId,
      @Nullable UUID counterpartyOrgUnitId,
      @Nullable BankPurpose purpose,
      @Nullable String clientId) {
    BankAccount account =
        Entities.require(accountRepository.findById(accountId), "Bank account not found");
    requireActiveForRequest(account);
    if (type == BankBookingRequestType.WITHDRAWAL || type == BankBookingRequestType.TRANSFER) {
      BankBookingGuards.requireDebitJustification(account, justification);
    }
    BankAccount targetAccount = null;
    if (type == BankBookingRequestType.TRANSFER) {
      if (targetAccountId == null) {
        throw new NotFoundException("A transfer request requires a destination account");
      }
      if (targetAccountId.equals(accountId)) {
        throw new BankConflictException(
            BankConflictException.CODE_BANK_SELF_TRANSFER,
            "Source and destination account of a transfer must differ");
      }
      targetAccount =
          Entities.require(
              accountRepository.findById(targetAccountId), "Destination account not found");
      requireActiveForRequest(targetAccount);
    }

    BankBookingRequest request = new BankBookingRequest();
    request.setAccount(account);
    request.setTargetAccount(targetAccount);
    request.setType(type);
    request.setAmount(amount);
    request.setNote(note);
    request.setJustification(justification);
    applyCounterparty(
        request, resolveRequestCounterparty(type, counterpartyUserId, counterpartyOrgUnitId));
    request.setStatus(BankBookingRequestStatus.PENDING);
    UUID requesterSub = authHelperService.currentUserId().orElse(null);
    String requesterHandle = resolveHandle(requesterSub);
    request.setRequestedBy(requesterSub);
    request.setRequesterHandle(requesterHandle);
    request.setRequiresOwnerApproval(requiresOwnerApproval);
    request.setApplicableLimit(applicableLimit);
    request.setRequiredApprover(requiredApprover);
    request.setSplitEnabled(splitEnabled);
    request.setSplitPercent(splitPercent);
    request.setPurposeKind(purpose == null ? null : purpose.kind());
    request.setPurposeRef(purpose == null ? null : purpose.ref());
    request.setClientId(clientId);
    BankBookingRequest saved = requestRepository.save(request);

    bankAuditService.record(
        BankAuditEventType.BOOKING_REQUEST_CREATED,
        account.getId(),
        null,
        requesterSub,
        type
            + " request "
            + plain(amount)
            + " aUEC "
            + shortId(saved.getId())
            + (requiresOwnerApproval ? " (needs approval)" : ""));

    OrgUnit orgUnit = account.getOrgUnit();
    eventPublisher.publishEvent(
        new BankBookingRequestCreatedEvent(
            saved.getId(),
            account.getId(),
            type,
            amount,
            account.getAccountNo(),
            requesterHandle,
            orgUnit == null ? null : orgUnit.getShorthand(),
            requesterSub));
    return toDto(saved);
  }

  /**
   * Resolves the registered Empf&auml;nger named on a {@code WITHDRAWAL} request into a
   * deletion-proof snapshot taken at request time (REQ-BANK-055). No free-text counterparty is
   * accepted here.
   *
   * @param type the movement kind; anything but {@code WITHDRAWAL} must not carry a counterparty
   * @param userId the named Empf&auml;nger, or {@code null}
   * @param orgUnitId the chosen org unit of that user, or {@code null}
   * @return the snapshot, or {@code null} when no counterparty was named
   * @throws BadRequestException when a counterparty is named on a non-withdrawal, an org unit is
   *     given without a user, or the org unit is not one of that user's memberships
   * @throws NotFoundException when the named user does not exist
   */
  @Nullable
  private CounterpartySnapshot resolveRequestCounterparty(
      @NotNull BankBookingRequestType type, @Nullable UUID userId, @Nullable UUID orgUnitId) {
    if (userId == null) {
      if (orgUnitId != null) {
        throw new BadRequestException("A counterparty org unit requires a counterparty");
      }
      return null;
    }
    if (type != BankBookingRequestType.WITHDRAWAL) {
      throw new BadRequestException(
          "Only a withdrawal request records a counterparty (Empfaenger)");
    }
    User user = Entities.require(userRepository.findById(userId), "Counterparty user not found");
    if (orgUnitId == null) {
      return new CounterpartySnapshot(user.getId(), user.getEffectiveName(), null, null);
    }
    OrgUnitMembershipOptionDto membership =
        orgUnitMembershipQueryService.listDirectMembershipOptions(userId).stream()
            .filter(option -> option.orgUnitId().equals(orgUnitId))
            .findFirst()
            .orElseThrow(() ->
                new BadRequestException(
                    "The selected org unit is not one of the counterparty's memberships"));
    return new CounterpartySnapshot(
        user.getId(), user.getEffectiveName(), membership.orgUnitId(), membership.orgUnitName());
  }

  /**
   * Resolves the org unit to stamp on a confirmed booking for the counterparty (REQ-BANK-055): the
   * stored choice while it is still one of the counterparty's memberships, else their current
   * primary unit, else none.
   *
   * @param request the request being confirmed
   * @param counterpartyId the resolved counterparty (the named Empf&auml;nger, else the requester),
   *     or {@code null} when the request carries neither
   * @return the org unit to record, or {@code null}
   */
  @Nullable
  private UUID confirmCounterpartyOrgUnitId(
      @NotNull BankBookingRequest request, @Nullable UUID counterpartyId) {
    if (counterpartyId == null) {
      return null;
    }
    UUID stored = request.getCounterpartyOrgUnitId();
    if (stored != null
        && orgUnitMembershipQueryService.listDirectMembershipOptions(counterpartyId).stream()
            .anyMatch(option -> option.orgUnitId().equals(stored))) {
      return stored;
    }
    return orgUnitMembershipQueryService
        .findPrimaryDirectMembershipOrgUnitId(counterpartyId)
        .orElse(null);
  }

  /**
   * Writes a resolved counterparty snapshot onto the request, clearing all four counterparty
   * columns when it is {@code null}.
   *
   * @param request the request to stamp
   * @param counterparty the resolved snapshot, or {@code null} to clear
   */
  private static void applyCounterparty(
      @NotNull BankBookingRequest request, @Nullable CounterpartySnapshot counterparty) {
    request.setCounterpartyUserId(counterparty == null ? null : counterparty.userId());
    request.setCounterpartyHandle(counterparty == null ? null : counterparty.handle());
    request.setCounterpartyOrgUnitId(counterparty == null ? null : counterparty.orgUnitId());
    request.setCounterpartyOrgUnitName(counterparty == null ? null : counterparty.orgUnitName());
  }

  /**
   * Pages the current caller's own booking requests in the given states (REQ-BANK-022). Per-user
   * isolation: it reads strictly the caller's {@code sub}, never a foreign request.
   *
   * @param statuses the lifecycle states to include (any-of)
   * @param pageable page, size and sort
   * @return the page; empty when unauthenticated or none exist
   */
  @NotNull
  public Page<BankBookingRequestDto> pageForCurrentRequester(
      @NotNull Set<BankBookingRequestStatus> statuses, @NotNull Pageable pageable) {
    return authHelperService
        .currentUserId()
        .map(sub ->
            requestRepository
                .findByRequestedByAndStatusIn(sub, statuses, pageable)
                .map(this::toDto))
        .orElseGet(() -> Page.empty(pageable));
  }

  /**
   * Applies the actor's correction to their own pending, not yet owner-approved request
   * (REQ-BANK-056), stamping the re-derived approval snapshot and renewing the open notices.
   *
   * <p>All guards run under the row lock; a foreign request is reported as not found.
   *
   * @param actor the requester the ownership is checked for
   * @param requestId the request to correct
   * @param update the corrected values plus the echoed version
   * @param requiresOwnerApproval the re-derived approval flag for the new amount
   * @param applicableLimit the re-derived applicable limit, or {@code null}
   * @param requiredApprover the re-derived approver class, or {@code null}
   * @return the updated request
   * @throws NotFoundException when the request does not exist or belongs to another user
   * @throws BankConflictException {@code BANK_REQUEST_NOT_PENDING} when already decided, {@code
   *     BANK_REQUEST_ALREADY_APPROVED} when the responsible holder already signed off
   * @throws BadRequestException on an inconsistent destination account or counterparty, or a
   *     missing Begr&uuml;ndung on a mandating account
   * @throws ObjectOptimisticLockingFailureException on a version mismatch (409)
   */
  @NotNull
  @Transactional
  public BankBookingRequestDto updateOwn(
      @NotNull BankActor actor,
      @NotNull UUID requestId,
      @NotNull UpdateBankBookingRequest update,
      boolean requiresOwnerApproval,
      @Nullable BigDecimal applicableLimit,
      @Nullable BankRequestApprover requiredApprover) {
    BankBookingRequest request = lockRequest(requestId);
    if (!policy.ownsRequest(actor, request)) {
      throw new NotFoundException("Booking request not found");
    }
    requireVersion(request, update.version());
    requirePending(request);
    if (request.isOwnerApprovalGranted()) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_REQUEST_ALREADY_APPROVED,
          "An approved request can no longer be edited; cancel it and raise a new one");
    }
    BankBookingRequestType type = request.getType();
    if (type == BankBookingRequestType.WITHDRAWAL || type == BankBookingRequestType.TRANSFER) {
      BankBookingGuards.requireDebitJustification(request.getAccount(), update.justification());
    }
    applyTargetAccount(request, type, update.targetAccountId());
    applyCounterparty(
        request,
        resolveRequestCounterparty(
            type, update.counterpartyUserId(), update.counterpartyOrgUnitId()));
    request.setAmount(update.amount());
    request.setNote(update.note());
    request.setJustification(update.justification());
    request.setRequiresOwnerApproval(requiresOwnerApproval);
    request.setApplicableLimit(applicableLimit);
    request.setRequiredApprover(requiredApprover);

    bankAuditService.record(
        BankAuditEventType.BOOKING_REQUEST_UPDATED,
        request.getAccount().getId(),
        null,
        request.getRequestedBy(),
        "edited request "
            + shortId(request.getId())
            + " to "
            + plain(update.amount())
            + " aUEC"
            + (requiresOwnerApproval ? " (needs approval)" : ""));
    BankAccount account = request.getAccount();
    OrgUnit orgUnit = account.getOrgUnit();
    eventPublisher.publishEvent(
        new BankBookingRequestUpdatedEvent(
            request.getId(),
            account.getId(),
            type,
            update.amount(),
            account.getAccountNo(),
            request.getRequesterHandle(),
            orgUnit == null ? null : orgUnit.getShorthand(),
            request.getRequestedBy()));
    return toDto(request);
  }

  /**
   * Re-points a {@code TRANSFER} request's destination account, enforcing the same shape rules the
   * create path applies: a transfer names an active destination that differs from its source, and a
   * deposit/withdrawal names none.
   *
   * @param request the request being edited
   * @param type the request's (immutable) movement kind
   * @param targetAccountId the corrected destination, or {@code null}
   * @throws BadRequestException when the destination is missing on a transfer or present otherwise
   * @throws BankConflictException {@code BANK_SELF_TRANSFER} when it equals the source
   * @throws NotFoundException when the destination account does not exist
   */
  private void applyTargetAccount(
      @NotNull BankBookingRequest request,
      @NotNull BankBookingRequestType type,
      @Nullable UUID targetAccountId) {
    if (type != BankBookingRequestType.TRANSFER) {
      if (targetAccountId != null) {
        throw new BadRequestException(
            "A non-transfer request must not carry a destination account");
      }
      request.setTargetAccount(null);
      return;
    }
    if (targetAccountId == null) {
      throw new BadRequestException("A transfer request requires a destination account");
    }
    if (targetAccountId.equals(request.getAccount().getId())) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_SELF_TRANSFER,
          "Source and destination account of a transfer must differ");
    }
    BankAccount target =
        Entities.require(
            accountRepository.findById(targetAccountId), "Destination account not found");
    requireActiveForRequest(target);
    request.setTargetAccount(target);
  }

  /**
   * Lists the foreign requests of a scope, newest first (REQ-BANK-046): the requests on its
   * candidate accounts that it shows, without those the four-eyes rule bars the caller from
   * deciding (REQ-BANK-062). Backs the "Fremde Anträge" tab.
   *
   * @param scope the caller's foreign-request scope from {@link BankAccessRules}
   * @return the requests the caller acts on; empty when the scope has no candidate account
   */
  @NotNull
  public List<BankBookingRequestDto> listForeign(
      @NotNull BankAccessRules.ForeignRequestScope scope) {
    if (scope.candidateAccountIds().isEmpty()) {
      return List.of();
    }
    return requestRepository
        .findByAccountIdInOrderByCreatedAtDesc(scope.candidateAccountIds())
        .stream()
        .filter(request -> !scope.rights().boundByFourEyes(request.getRequestedBy()))
        .filter(request -> scope.shows(request.getRequiredApprover(), request.getAccount().getId()))
        .map(this::toDto)
        .toList();
  }

  /**
   * Cancels the actor's own pending request (REQ-BANK-022); a foreign request is not found, a
   * decided one a 409. Fires {@link BankBookingRequestCancelledEvent}, which clears the staff's
   * stale "new booking request" notifications (REQ-NOTIF-018).
   *
   * @param actor the requester the ownership is checked for
   * @param requestId the request to cancel
   * @param version the echoed optimistic-locking version
   * @return the cancelled request
   * @throws NotFoundException when the request does not exist or belongs to another user
   * @throws BankConflictException with {@code BANK_REQUEST_NOT_PENDING} when already decided
   * @throws ObjectOptimisticLockingFailureException on a version mismatch (409)
   */
  @NotNull
  @Transactional
  public BankBookingRequestDto cancelOwn(
      @NotNull BankActor actor, @NotNull UUID requestId, long version) {
    BankBookingRequest request = lockRequest(requestId);
    if (!policy.ownsRequest(actor, request)) {
      throw new NotFoundException("Booking request not found");
    }
    requireVersion(request, version);
    requirePending(request);
    request.setStatus(BankBookingRequestStatus.CANCELLED);
    request.setDecidedAt(Instant.now());
    bankAuditService.record(
        BankAuditEventType.BOOKING_REQUEST_CANCELLED,
        request.getAccount().getId(),
        null,
        request.getRequestedBy(),
        "cancelled request " + shortId(request.getId()));
    eventPublisher.publishEvent(
        new BankBookingRequestCancelledEvent(
            request.getId(), request.getAccount().getId(), request.getRequestedBy()));
    notifyPurposeObserver(request, (observer, ref) -> observer.onCancelled(request.getId(), ref));
    return toDto(request);
  }

  /**
   * Cancels a pending request on behalf of the module that raised it for a purpose (REQ-BANK-061),
   * for example because the deal it pays for was cancelled. Joins the caller's transaction, skips
   * the requester check and tells the purpose observer like any other cancellation.
   *
   * @param requestId the request to cancel
   * @param kind the purpose kind the request must carry
   * @throws NotFoundException when the request does not exist or carries another purpose kind
   * @throws BankConflictException with {@code BANK_REQUEST_NOT_PENDING} when already decided
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void cancelForPurpose(@NotNull UUID requestId, @NotNull BankPurposeKind kind) {
    BankBookingRequest request = lockRequest(requestId);
    if (request.getPurposeKind() != kind) {
      throw new NotFoundException("Booking request not found");
    }
    requirePending(request);
    UUID actor = authHelperService.currentUserId().orElse(null);
    request.setStatus(BankBookingRequestStatus.CANCELLED);
    request.setDecidedAt(Instant.now());
    bankAuditService.record(
        BankAuditEventType.BOOKING_REQUEST_CANCELLED,
        request.getAccount().getId(),
        null,
        actor,
        "cancelled request " + shortId(request.getId()));
    eventPublisher.publishEvent(
        new BankBookingRequestCancelledEvent(request.getId(), request.getAccount().getId(), actor));
    notifyPurposeObserver(request, (observer, ref) -> observer.onCancelled(request.getId(), ref));
  }

  /**
   * Tells the observer that owns the request's purpose kind how the request ended, inside the
   * bank's transaction (REQ-BANK-063); nothing for a request without a purpose.
   *
   * @param request the request that just reached its terminal state
   * @param call the observer call, given the observer and the stored purpose reference
   */
  private void notifyPurposeObserver(
      @NotNull BankBookingRequest request,
      @NotNull BiConsumer<BookingRequestPurposeObserver, UUID> call) {
    BankPurposeKind kind = request.getPurposeKind();
    UUID ref = request.getPurposeRef();
    if (kind == null || ref == null) {
      return;
    }
    purposeObservers
        .orderedStream()
        .filter(observer -> observer.kind() == kind)
        .forEach(observer -> call.accept(observer, ref));
  }

  /**
   * One page of booking requests in any of the given lifecycle states for the bank-staff
   * confirmation queue with the parallel status filter (REQ-BANK-023). Management sees every
   * account; an employee sees only requests on accounts they are granted on. This is a pure
   * bank-staff surface — it consults grants, never org-unit scope. An empty status set yields an
   * empty page (the caller deselected every filter). Every row carries {@code callerMayConfirm},
   * decided by the caller's {@link BankStaffRights} with no read per row.
   *
   * @param statuses the lifecycle states to include (any-of, e.g. {@code PENDING})
   * @param pageable page, size and whitelisted sort
   * @return one page of requests visible to the caller in any of those states
   */
  @NotNull
  public Page<BankBookingRequestDto> listQueue(
      @NotNull Set<BankBookingRequestStatus> statuses, @NotNull Pageable pageable) {
    if (statuses.isEmpty()) {
      return Page.empty(pageable);
    }
    BankStaffRights rights = staffRights();
    return pageQueue(statuses, pageable, rights)
        .map(request ->
            BankBookingRequestDtos.of(
                request,
                clientNames.nameOf(request),
                BankBookingRequestDtos.callerMayConfirm(request, rights)));
  }

  /**
   * Fails the decision when the four-eyes rule bars the current caller from granting or confirming
   * the request (REQ-BANK-062).
   *
   * @param request the request being decided
   * @param rights the caller's staff rights
   * @throws BankConflictException with {@code BANK_FOUR_EYES_REQUIRED} when the caller raised it
   */
  private static void requireSecondPerson(
      @NotNull BankBookingRequest request, @NotNull BankStaffRights rights) {
    if (rights.boundByFourEyes(request.getRequestedBy())) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_FOUR_EYES_REQUIRED,
          "The requester of a booking request cannot approve or confirm it");
    }
  }

  /**
   * Reads one page of the queue: every account for management, the granted accounts otherwise.
   *
   * @param statuses the non-empty lifecycle states to include
   * @param pageable page, size and whitelisted sort
   * @param rights the caller's staff rights
   * @return one page of request entities visible to the caller
   */
  @NotNull
  private Page<BankBookingRequest> pageQueue(
      @NotNull Set<BankBookingRequestStatus> statuses,
      @NotNull Pageable pageable,
      @NotNull BankStaffRights rights) {
    if (rights.management()) {
      return requestRepository.findByStatusIn(statuses, pageable);
    }
    if (rights.grantedAccountIds().isEmpty()) {
      return Page.empty(pageable);
    }
    return requestRepository.findByStatusInAndAccountIdIn(
        statuses, rights.grantedAccountIds(), pageable);
  }

  /**
   * The current caller's staff rights.
   *
   * @return the rights of {@link BankCaller#actor()}
   */
  @NotNull
  private BankStaffRights staffRights() {
    return policy.staffRights(bankCaller.actor());
  }

  /**
   * Confirms a pending request: a bank employee records the holder and books the movement onto the
   * ledger (REQ-BANK-023). The booking reuses {@link BankLedgerService}, so the
   * holder-at-confirmation and overdraft-at-confirmation guards apply exactly as for a direct
   * deposit/withdrawal, then the request flips to {@code CONFIRMED} carrying the recorded holder
   * and the resulting transaction.
   *
   * @param requestId the request to confirm
   * @param holderId the holder the employee records for the booking (source holder for a transfer)
   * @param destinationHolderId the destination holder for a transfer; {@code null} otherwise
   * @param staffNote the confirming employee's own note ("Notiz Bankmitarbeiter", REQ-BANK-054),
   *     snapshotted on the request and copied onto the booked transaction; {@code null} when none
   * @param ownerApprovalConfirmed the over-limit "approval by responsible holder obtained"
   *     attestation (REQ-BANK-041); required when the request needs approval
   * @param version the echoed optimistic-locking version
   * @return the confirmed request
   * @throws NotFoundException when the request does not exist
   * @throws BankApproverNoteRequiredException when the request needs approval, none was recorded in
   *     the app and {@code staffNote} is blank, so nothing names the approver (REQ-BANK-041,
   *     ADR-0353)
   * @throws AccessDeniedException when the employee lacks the matching per-account capability
   * @throws BankConflictException with {@code BANK_REQUEST_NOT_PENDING}, {@code
   *     BANK_FOUR_EYES_REQUIRED} (the caller raised the request and is no admin, REQ-BANK-062),
   *     {@code BANK_OWNER_APPROVAL_REQUIRED}, or a ledger conflict ({@code BANK_ACCOUNT_CLOSED},
   *     {@code BANK_OVERDRAFT}, {@code BANK_SELF_TRANSFER}); the holder may go negative
   *     (REQ-BANK-006, ADR-0039)
   * @throws ObjectOptimisticLockingFailureException on a version mismatch (409)
   */
  @NotNull
  @Transactional
  public BankBookingRequestDto confirm(
      @NotNull UUID requestId,
      @NotNull UUID holderId,
      @Nullable UUID destinationHolderId,
      boolean ownerApprovalConfirmed,
      @Nullable String staffNote,
      long version) {
    BankBookingRequest request = lockRequest(requestId);
    requireVersion(request, version);
    requirePending(request);
    BankStaffRights rights = staffRights();
    requireSecondPerson(request, rights);
    BankOverLimitConfirmGate.require(request, ownerApprovalConfirmed, staffNote);
    UUID accountId = request.getAccount().getId();
    if (!rights.canBook(request.getType(), accountId)) {
      throw new AccessDeniedException(
          "The caller lacks the bank capability to confirm this request");
    }

    UUID namedCounterparty = request.getCounterpartyUserId();
    UUID requesterId = namedCounterparty != null ? namedCounterparty : request.getRequestedBy();
    UUID counterpartyOrgUnitId = confirmCounterpartyOrgUnitId(request, requesterId);

    BankTransactionDto booked =
        switch (request.getType()) {
          case DEPOSIT ->
              bankLedgerService.bookDeposit(
                  new BankDepositRequest(
                      accountId,
                      holderId,
                      request.getAmount(),
                      request.getNote(),
                      staffNote,
                      request.isSplitEnabled(),
                      request.getSplitPercent(),
                      requesterId,
                      counterpartyOrgUnitId,
                      null));
          case WITHDRAWAL ->
              bankLedgerService.bookWithdrawal(
                  new BankWithdrawalRequest(
                      accountId,
                      holderId,
                      request.getAmount(),
                      request.getNote(),
                      request.getJustification(),
                      staffNote,
                      requesterId,
                      counterpartyOrgUnitId,
                      false,
                      null));
          case TRANSFER -> {
            BankAccount target = request.getTargetAccount();
            if (target == null || destinationHolderId == null) {
              throw new NotFoundException(
                  "A transfer confirmation requires the destination account and holder");
            }
            yield bankLedgerService.bookTransfer(
                new BankTransferRequest(
                    accountId,
                    holderId,
                    target.getId(),
                    destinationHolderId,
                    request.getAmount(),
                    request.getNote(),
                    request.getJustification(),
                    staffNote,
                    false),
                true);
          }
        };

    BankHolder holder =
        Entities.require(holderRepository.findById(holderId), "Bank holder not found");
    BankTransaction transaction =
        Entities.require(transactionRepository.findById(booked.id()), "Bank transaction not found");
    UUID decider = authHelperService.currentUserId().orElse(null);
    request.setHolder(holder);
    request.setResultingTransaction(transaction);
    request.setStatus(BankBookingRequestStatus.CONFIRMED);
    request.setStaffNote(staffNote);
    request.setDecidedBy(decider);
    request.setDeciderHandle(resolveHandle(decider));
    request.setDecidedAt(Instant.now());

    bankAuditService.record(
        BankAuditEventType.BOOKING_REQUEST_CONFIRMED,
        accountId,
        booked.id(),
        request.getRequestedBy(),
        "confirmed "
            + request.getType()
            + " request "
            + shortId(request.getId())
            + " @"
            + holder.getHandle());
    if (request.isRequiresOwnerApproval()) {
      bankAuditService.record(
          BankAuditEventType.BOOKING_REQUEST_OWNER_APPROVAL_CONFIRMED,
          accountId,
          booked.id(),
          request.getRequestedBy(),
          "owner approval confirmed for request " + shortId(request.getId()));
    }
    eventPublisher.publishEvent(
        new BankBookingRequestConfirmedEvent(
            request.getId(),
            request.getAccount().getId(),
            request.getAccount().getAccountNo(),
            request.getAmount(),
            request.getRequestedBy(),
            decider));
    notifyPurposeObserver(
        request,
        (observer, ref) -> observer.onConfirmed(request.getId(), ref, transaction.getId()));
    return toDto(request);
  }

  /**
   * Rejects a pending request (REQ-BANK-023): the bank employee declines it with a reason; no money
   * moves. Gated by visibility of the account ({@code canSee}); the request flips to {@code
   * REJECTED}.
   *
   * @param requestId the request to reject
   * @param reason the rejection reason
   * @param version the echoed optimistic-locking version
   * @return the rejected request
   * @throws NotFoundException when the request does not exist
   * @throws AccessDeniedException when the employee may not see the account
   * @throws BankConflictException with {@code BANK_REQUEST_NOT_PENDING} when already decided
   * @throws ObjectOptimisticLockingFailureException on a version mismatch (409)
   */
  @NotNull
  @Transactional
  public BankBookingRequestDto reject(
      @NotNull UUID requestId, @NotNull String reason, long version) {
    BankBookingRequest request = lockRequest(requestId);
    requireVersion(request, version);
    requirePending(request);
    UUID accountId = request.getAccount().getId();
    if (!staffRights().canSee(accountId)) {
      throw new AccessDeniedException("The caller may not see the request's account");
    }
    UUID decider = authHelperService.currentUserId().orElse(null);
    request.setStatus(BankBookingRequestStatus.REJECTED);
    request.setRejectReason(reason);
    request.setDecidedBy(decider);
    request.setDeciderHandle(resolveHandle(decider));
    request.setDecidedAt(Instant.now());
    bankAuditService.record(
        BankAuditEventType.BOOKING_REQUEST_REJECTED,
        accountId,
        null,
        request.getRequestedBy(),
        "rejected request " + shortId(request.getId()));
    eventPublisher.publishEvent(
        new BankBookingRequestRejectedEvent(
            request.getId(),
            request.getAccount().getId(),
            request.getAccount().getAccountNo(),
            request.getAmount(),
            reason,
            request.getRequestedBy(),
            decider));
    notifyPurposeObserver(request, (observer, ref) -> observer.onRejected(request.getId(), ref));
    return toDto(request);
  }

  /**
   * Grants or revokes the responsible holder's approval on an already-locked request (REQ-BANK-041)
   * and audits it. Must run inside the caller's transaction, after {@link
   * OrgUnitBankRequestService} has authorized the caller.
   *
   * @param request the locked, managed request
   * @param granted whether to grant ({@code true}) or revoke ({@code false}) the approval
   * @return the updated request
   * @throws BadRequestException when granting approval to a request that needs none
   * @throws BankConflictException with {@code BANK_REQUEST_NOT_PENDING} when no longer pending, or
   *     {@code BANK_FOUR_EYES_REQUIRED} when a non-admin grants their own request (REQ-BANK-062)
   */
  @NotNull
  @Transactional(propagation = Propagation.MANDATORY)
  public BankBookingRequestDto applyOwnerApprovalWithinTransaction(
      @NotNull BankBookingRequest request, boolean granted) {
    requirePending(request);
    if (granted) {
      requireSecondPerson(request, staffRights());
    }
    if (granted && !request.isRequiresOwnerApproval()) {
      throw new BadRequestException(
          "This request does not require the responsible holder's approval");
    }
    UUID actor = authHelperService.currentUserId().orElse(null);
    request.setOwnerApprovalGranted(granted);
    request.setOwnerApprovalGrantedBy(granted ? actor : null);
    request.setOwnerApprovalGrantedByHandle(granted ? resolveHandle(actor) : null);
    request.setOwnerApprovalGrantedAt(granted ? Instant.now() : null);
    bankAuditService.record(
        granted
            ? BankAuditEventType.BOOKING_REQUEST_OWNER_APPROVAL_GRANTED
            : BankAuditEventType.BOOKING_REQUEST_OWNER_APPROVAL_REVOKED,
        request.getAccount().getId(),
        null,
        request.getRequestedBy(),
        (granted ? "granted" : "revoked")
            + " owner approval for request "
            + shortId(request.getId()));
    eventPublisher.publishEvent(
        granted
            ? BankNotices.approved(
                request.getId(),
                request.getAccount().getId(),
                request.getRequestedBy(),
                request.getType(),
                request.getAmount(),
                request.getAccount().getAccountNo(),
                Objects.requireNonNull(request.getOwnerApprovalGrantedByHandle()),
                actor)
            : BankNotices.approvalRevoked(
                request.getId(),
                request.getAccount().getId(),
                request.getType(),
                request.getAmount(),
                request.getAccount().getAccountNo(),
                request.getRequesterHandle(),
                actor));
    return toDto(request);
  }

  /**
   * Whether the given account has at least one open ({@code PENDING}) booking request — the input
   * to the close-account guard (REQ-BANK-025).
   *
   * @param accountId the account to probe
   * @return {@code true} when an open request exists
   */
  public boolean hasOpenRequests(@NotNull UUID accountId) {
    return requestRepository.existsByAccountIdAndStatus(
        accountId, BankBookingRequestStatus.PENDING);
  }

  /**
   * Loads and pessimistically locks a request for a decision, or fails with 404.
   *
   * @param requestId the request id
   * @return the locked, managed request
   * @throws NotFoundException when the request does not exist
   */
  private BankBookingRequest lockRequest(@NotNull UUID requestId) {
    return Entities.require(
        requestRepository.findByIdForUpdate(requestId), "Booking request not found");
  }

  /**
   * Fails the decision when the echoed version no longer matches the stored one (concurrent
   * change).
   *
   * @param request the locked request
   * @param version the echoed version
   * @throws ObjectOptimisticLockingFailureException on a mismatch
   */
  private void requireVersion(@NotNull BankBookingRequest request, long version) {
    OptimisticLock.checkRequired(
        request.getVersion(), version, BankBookingRequest.class, request.getId());
  }

  /**
   * Rejects a decision on a request that is no longer pending (already
   * confirmed/rejected/cancelled) — blocks double-decisions (REQ-BANK-023).
   *
   * @param request the locked request
   * @throws BankConflictException with {@code BANK_REQUEST_NOT_PENDING} when not pending
   */
  private void requirePending(@NotNull BankBookingRequest request) {
    if (request.getStatus() != BankBookingRequestStatus.PENDING) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_REQUEST_NOT_PENDING,
          "The booking request is no longer pending",
          Map.of("status", request.getStatus().name()));
    }
  }

  /**
   * Rejects raising a request against a closed account — for a transfer this guards both the source
   * and the destination (REQ-BANK-002/-040).
   *
   * @param account the account to check
   * @throws BankConflictException with {@code BANK_ACCOUNT_CLOSED} on a non-active account
   */
  private void requireActiveForRequest(@NotNull BankAccount account) {
    if (account.getStatus() != BankAccountStatus.ACTIVE) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_ACCOUNT_CLOSED,
          "The account is closed and accepts no booking requests",
          Map.of("accountNo", account.getAccountNo()));
    }
  }

  /**
   * Snapshots a user's effective name for the requester/decider handle column, falling back to a
   * neutral marker when the user cannot be resolved (never a PII leak).
   *
   * @param userId the user id, possibly {@code null}
   * @return the effective name, or {@code "unknown"}
   */
  private String resolveHandle(@Nullable UUID userId) {
    return Optional.ofNullable(userId)
        .flatMap(userRepository::findById)
        .map(User::getEffectiveName)
        .orElse("unknown");
  }

  /**
   * Maps a request entity to its wire shape on a read that does not judge the caller's confirm
   * capability, so {@code callerMayConfirm} is {@code null}.
   *
   * @param request the request entity
   * @return the wire DTO
   */
  @NotNull
  private BankBookingRequestDto toDto(@NotNull BankBookingRequest request) {
    return BankBookingRequestDtos.of(request, clientNames.nameOf(request), null);
  }

  /**
   * Shortens an id to its first hex group for compact audit details.
   *
   * @param id the id
   * @return the first id segment
   */
  private static String shortId(@NotNull UUID id) {
    String s = id.toString();
    int dash = s.indexOf('-');
    return dash < 0 ? s : s.substring(0, dash);
  }
}

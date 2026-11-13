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
import de.greluc.krt.profit.basetool.backend.bank.api.BankAdmission;
import de.greluc.krt.profit.basetool.backend.bank.api.BankApproverNoteRequiredException;
import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankConflictException;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedAttempt;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedEffectHandler.Action;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedOutcome;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurpose;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurposeKind;
import de.greluc.krt.profit.basetool.backend.bank.api.BankRequestApprover;
import de.greluc.krt.profit.basetool.backend.bank.api.BankStaffRights;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestCancelledEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestRejectedEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankBookingRequestUpdatedEvent;
import de.greluc.krt.profit.basetool.backend.bank.api.events.BankNotices;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.OptimisticLock;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationCauseCollector;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnit;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipQueryService;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Manages the lifecycle of versioned Bank booking requests (REQ-BANK-022/-023). */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class BankBookingRequestService {

  private final BankBookingRequestRepository requestRepository;
  private final BankAccountRepository accountRepository;
  private final BankHolderRepository holderRepository;
  private final BankTransactionRepository transactionRepository;
  private final BankRequestCreation creation;
  private final BankRequestPostings ordinaryPostings;
  private final BankCaller bankCaller;
  private final BankAccessRules policy;
  private final BankAuditService bankAuditService;
  private final AuthHelperService authHelperService;
  private final UserRepository userRepository;
  private final OrgUnitMembershipQueryService orgUnitMembershipQueryService;
  private final ApplicationEventPublisher eventPublisher;
  private final BankPurposeObservers purposeObservers;
  private final BankRequestClientNames clientNames;
  private final BankLinkedDecisions linkedDecisions;
  private final BankLinkedBookings linkedBookings;
  private final BankLinkedRequestLiveSync linkedLiveSync;
  private final NotificationCauseCollector notificationCauses;
  private final BankRequestConfirmation confirmation;

  /**
   * Persists, audits and announces a pending request for the explicit applicant in the draft.
   *
   * @param draft the requester, the payload and the approval snapshot
   * @return the created request
   * @throws NotFoundException when the source or destination account does not exist
   * @throws BankConflictException with {@code BANK_ACCOUNT_CLOSED} on a closed account or {@code
   *     BANK_SELF_TRANSFER} when source equals destination
   * @throws BadRequestException when a counterparty is named on a non-withdrawal, or its org unit
   *     is not one of that user's memberships
   */
  @NotNull
  @Transactional
  public BankBookingRequestDto create(@NotNull BankBookingRequestDraft draft) {
    BankBookingRequestType type = draft.type();
    BankAccount account =
        Entities.require(accountRepository.findById(draft.accountId()), "Bank account not found");
    requireActiveForRequest(account);
    if (type == BankBookingRequestType.WITHDRAWAL || type == BankBookingRequestType.TRANSFER) {
      BankBookingGuards.requireDebitJustification(account, draft.justification());
    }
    BankAccount targetAccount = null;
    if (type == BankBookingRequestType.TRANSFER) {
      UUID targetAccountId = draft.targetAccountId();
      if (targetAccountId == null) {
        throw new NotFoundException("A transfer request requires a destination account");
      }
      if (targetAccountId.equals(draft.accountId())) {
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
    request.setAmount(draft.amount());
    request.setNote(draft.note());
    request.setJustification(draft.justification());
    request.applyCounterparty(
        resolveRequestCounterparty(
            type, draft.counterpartyUserId(), draft.counterpartyOrgUnitId()));
    request.setStatus(BankBookingRequestStatus.PENDING);
    UUID requesterSub = draft.requester().userId();
    String requesterHandle = creation.resolveHandle(requesterSub);
    request.setRequestedBy(requesterSub);
    request.setRequesterHandle(requesterHandle);
    BankAdmission.Admitted routing = draft.routing();
    boolean requiresOwnerApproval = routing.requiresApproval();
    request.setRequiresOwnerApproval(requiresOwnerApproval);
    request.setApplicableLimit(routing.applicableLimit());
    request.setRequiredApprover(routing.requiredApprover());
    request.setSplitEnabled(draft.splitEnabled());
    request.setSplitPercent(draft.splitPercent());
    BankPurpose purpose = draft.purpose();
    request.setPurposeKind(purpose == null ? null : purpose.kind());
    request.setPurposeRef(purpose == null ? null : purpose.ref());
    BankRequestOrigin origin = draft.origin();
    request.setClientId(origin == null ? null : origin.clientId());
    request.setIdempotencyKey(origin == null ? null : origin.idempotencyKey());
    return toDto(creation.saveAndAnnounce(request));
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
    return RegisteredCounterpartyResolver.resolve(
        userRepository, orgUnitMembershipQueryService, userId, orgUnitId);
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
        .map(sub -> toDtos(requestRepository.findByRequestedByAndStatusIn(sub, statuses, pageable)))
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
   * @throws BadRequestException on an inconsistent destination account or counterparty, a missing
   *     Begr&uuml;ndung on a mandating account, or a changed amount, destination or recipient of a
   *     purpose-bound request
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
    request.requirePending();
    if (request.isOwnerApprovalGranted()) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_REQUEST_ALREADY_APPROVED,
          "An approved request can no longer be edited; cancel it and raise a new one");
    }
    if (request.getLinkedAttempt() != null) {
      throw new BadRequestException("Linked Bank terms must be corrected through their purpose");
    }
    PurposeBoundCorrections.requireTermsKept(request, update);
    BankBookingRequestType type = request.getType();
    BankAccount account = Objects.requireNonNull(request.getAccount());
    if (type == BankBookingRequestType.WITHDRAWAL || type == BankBookingRequestType.TRANSFER) {
      BankBookingGuards.requireDebitJustification(account, update.justification());
    }
    applyTargetAccount(request, type, update.targetAccountId());
    UUID counterpartyId = update.counterpartyUserId();
    UUID counterpartyUnit = update.counterpartyOrgUnitId();
    if (!request.namesCounterparty(counterpartyId, counterpartyUnit)) {
      request.applyCounterparty(resolveRequestCounterparty(type, counterpartyId, counterpartyUnit));
    }
    request.setAmount(update.amount());
    request.setNote(update.note());
    request.setJustification(update.justification());
    request.setRequiresOwnerApproval(requiresOwnerApproval);
    request.setApplicableLimit(applicableLimit);
    request.setRequiredApprover(requiredApprover);

    bankAuditService.record(
        BankAuditEventType.BOOKING_REQUEST_UPDATED,
        account.getId(),
        null,
        request.getRequestedBy(),
        "edited request "
            + request.shortId()
            + " to "
            + plain(update.amount())
            + " aUEC"
            + (requiresOwnerApproval ? " (needs approval)" : ""));
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
    if (targetAccountId.equals(request.accountId())) {
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
    List<BankBookingRequest> shown =
        requestRepository
            .findByAccountIdInOrderByCreatedAtDesc(scope.candidateAccountIds())
            .stream()
            .filter(scope::approves)
            .toList();

    Function<BankBookingRequest, String> names = clientNames.forRequests(shown);
    return shown.stream()
        .map(request -> BankBookingRequestDtos.of(request, names.apply(request), null))
        .toList();
  }

  /**
   * Cancels the actor's own pending request (REQ-BANK-022); a foreign request is not found, a
   * decided one a 409. Fires {@link BankBookingRequestCancelledEvent}, which clears the staff's
   * stale "new booking request" notifications (REQ-NOTIF-018).
   *
   * @param requester the member the ownership is checked for, or {@code null} for none
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
      @Nullable UUID requester, @NotNull UUID requestId, long version) {
    BankLinkedDecisions.Prepared linked =
        linkedDecisions.forRequest(requestId, Action.CANCEL, null);
    return cancelOwnRequest(requester, requestId, version, linked);
  }

  /**
   * Cancels an ordinary Exchange request for its explicit actor.
   *
   * @param requester the admitted Exchange actor
   * @param requestId the request to cancel
   * @param version the echoed version
   * @return the cancelled request
   */
  @NotNull
  @Transactional
  BankBookingRequestDto cancelOwnExchange(
      @Nullable UUID requester, @NotNull UUID requestId, long version) {
    return cancelOwnRequest(requester, requestId, version, null);
  }

  /**
   * Cancels the actor's request under the row lock and its prepared purpose, when present.
   *
   * @param requester the owner whose request may be cancelled
   * @param requestId the request to cancel
   * @param version the echoed version
   * @param linked the prepared linked owner, if any
   * @return the cancelled request
   */
  @NotNull
  private BankBookingRequestDto cancelOwnRequest(
      @Nullable UUID requester,
      @NotNull UUID requestId,
      long version,
      @Nullable BankLinkedDecisions.Prepared linked) {
    BankBookingRequest request = lockRequest(requestId);
    if (!policy.ownsRequest(requester, request)
        || (linked == null && request.getLinkedAttempt() != null)) {
      throw new NotFoundException("Booking request not found");
    }
    requireVersion(request, version);
    cancel(request, request.getRequestedBy(), linked);
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
    if (request.getPurposeKind() != kind || request.getLinkedAttempt() != null) {
      throw new NotFoundException("Booking request not found");
    }
    cancel(request, authHelperService.currentUserId().orElse(null), null);
  }

  /**
   * Cancels the exact request on behalf of its required linked handler.
   *
   * @param requestId the request to cancel
   * @param attempt the exact attempt and purpose
   * @param action the ordinary or already-guarded terminal cancellation
   */
  @Transactional(propagation = Propagation.MANDATORY)
  void cancelLinked(
      @NotNull UUID requestId, @NotNull BankLinkedAttempt attempt, @NotNull Action action) {
    BankLinkedDecisions.Prepared linked =
        Objects.requireNonNull(linkedDecisions.forRequest(requestId, action, attempt));
    cancel(lockRequest(requestId), linked.actor(), linked);
  }

  /**
   * Locks the exact failed request before a replacement can acquire accounts.
   *
   * @param requestId the failed request
   * @param attempt its exact linked identity
   * @return the failed request retaining the original payment evidence
   */
  @NotNull
  @Transactional(propagation = Propagation.MANDATORY)
  BankBookingRequest replacementSource(
      @NotNull UUID requestId, @NotNull BankLinkedAttempt attempt) {
    return requireFailedAttempt(lockRequest(requestId), attempt);
  }

  /**
   * Reads the exact failed attempt and its retained terms for a replacement quote.
   *
   * @param requestId the failed request
   * @param attempt its exact linked identity
   * @return the failed request's retained evidence
   */
  @NotNull
  BankBookingRequest previewReplacementSource(
      @NotNull UUID requestId, @NotNull BankLinkedAttempt attempt) {
    return requireFailedAttempt(
        Entities.require(requestRepository.findById(requestId), "Booking request not found"),
        attempt);
  }

  private static BankBookingRequest requireFailedAttempt(
      BankBookingRequest request, BankLinkedAttempt attempt) {
    BankLinkedAttemptRecord previous = request.getLinkedAttempt();
    if (previous == null || !previous.identifies(attempt)) {
      throw new NotFoundException("Booking request not found");
    }
    if (request.getStatus() != BankBookingRequestStatus.REJECTED
        && request.getStatus() != BankBookingRequestStatus.CANCELLED) {
      throw new BusinessConflictException(
          "Only a rejected or cancelled Bank request can be replaced");
    }
    return request;
  }

  /**
   * Cancels a locked request and applies its synchronous outcome.
   *
   * @param request the pending request
   * @param actor the authenticated actor
   * @param linked the prepared linked handler, if any
   */
  private void cancel(
      @NotNull BankBookingRequest request,
      @Nullable UUID actor,
      @Nullable BankLinkedDecisions.Prepared linked) {
    request.requirePending();
    request.setStatus(BankBookingRequestStatus.CANCELLED);
    request.setDecidedAt(Instant.now());
    bankAuditService.record(
        BankAuditEventType.BOOKING_REQUEST_CANCELLED,
        request.accountId(),
        null,
        actor,
        "cancelled request " + request.shortId());
    var event = new BankBookingRequestCancelledEvent(request.getId(), request.accountId(), actor);
    if (linked != null) {
      notificationCauses.append(request.getId(), List.of(event));
      linked.complete(new BankLinkedOutcome.Cancelled(request.getId()));
      linkedLiveSync.changed();
    } else {
      eventPublisher.publishEvent(event);
      purposeObservers.notify(
          request, (observer, ref) -> observer.onCancelled(request.getId(), ref));
    }
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
    Page<BankBookingRequest> page = pageQueue(statuses, pageable, rights);
    Function<BankBookingRequest, String> names = clientNames.forRequests(page.getContent());
    return page.map(request ->
        BankBookingRequestDtos.of(
            request,
            names.apply(request),
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
    if (!rights.staff()) {
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
      @Nullable UUID holderId,
      @Nullable UUID destinationHolderId,
      boolean ownerApprovalConfirmed,
      @Nullable String staffNote,
      long version) {
    BankLinkedDecisions.Prepared linked =
        linkedDecisions.forRequest(requestId, Action.CONFIRM, null);
    BankBookingRequest request = lockRequest(requestId);
    requireVersion(request, version);
    request.requirePending();
    if (linked != null) {
      linkedBookings.lockConfirmationAccounts(request);
    } else if (holderId == null) {
      throw new BadRequestException("A custodian is required for an ordinary Bank request");
    }
    BankStaffRights rights = linked == null ? staffRights() : linkedDecisions.rights();
    requireSecondPerson(request, rights);
    BankOverLimitConfirmGate.require(request, ownerApprovalConfirmed, staffNote);
    if (!rights.canBook(request.getType(), request.accountId())) {
      throw new AccessDeniedException(
          "The caller lacks the bank capability to confirm this request");
    }

    if (linked != null) {
      return confirmLinked(request, holderId, destinationHolderId, staffNote, linked);
    }
    UUID ordinaryHolderId = Objects.requireNonNull(holderId);
    BankTransactionDto booked =
        ordinaryPostings.book(request, ordinaryHolderId, destinationHolderId, staffNote);
    BankHolder holder =
        Entities.require(holderRepository.findById(ordinaryHolderId), "Bank holder not found");
    BankTransaction transaction =
        Entities.require(transactionRepository.findById(booked.id()), "Bank transaction not found");
    UUID decider = authHelperService.currentUserId().orElse(null);
    eventPublisher.publishEvent(
        confirmation.record(request, holder, transaction, staffNote, decider));
    purposeObservers.notify(
        request,
        (observer, ref) -> observer.onConfirmed(request.getId(), ref, transaction.getId()));
    return toDto(request);
  }

  /**
   * Confirms fixed linked terms with the prepared owner in the same transaction.
   *
   * @param request the pending request
   * @param holderId the echoed source holder
   * @param destinationHolderId the echoed destination holder
   * @param staffNote the private staff note
   * @param linked the prepared linked owner
   * @return the confirmed request
   */
  @NotNull
  private BankBookingRequestDto confirmLinked(
      @NotNull BankBookingRequest request,
      @Nullable UUID holderId,
      @Nullable UUID destinationHolderId,
      @Nullable String staffNote,
      @NotNull BankLinkedDecisions.Prepared linked) {
    var confirmed =
        linkedBookings.confirm(request, holderId, destinationHolderId, staffNote, linked.actor());
    BankLinkedOutcome outcome = confirmed.outcome();
    BankTransaction transaction = null;
    UUID causeId;
    if (outcome instanceof BankLinkedOutcome.Posted posted) {
      causeId = posted.transactionId();
      transaction =
          Entities.require(transactionRepository.findById(causeId), "Bank transaction not found");
    } else if (outcome instanceof BankLinkedOutcome.Authorised authorised) {
      causeId = authorised.authorisationId();
      request.setAuthorisationId(causeId);
    } else {
      throw new IllegalStateException("A confirmed request requires a final Bank outcome");
    }
    notificationCauses.appendPreferred(
        causeId,
        confirmation.record(request, confirmed.holder(), transaction, staffNote, linked.actor()));
    linked.complete(outcome);
    linkedLiveSync.changed();
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
    BankLinkedDecisions.Prepared linked =
        linkedDecisions.forRequest(requestId, Action.REJECT, null);
    BankBookingRequest request = lockRequest(requestId);
    requireVersion(request, version);
    request.requirePending();
    UUID accountId = request.accountId();
    BankStaffRights rights = linked == null ? staffRights() : linkedDecisions.rights();
    if (accountId == null ? !rights.staff() : !rights.canSee(accountId)) {
      throw new AccessDeniedException("The caller may not see the request");
    }
    UUID decider = authHelperService.currentUserId().orElse(null);
    request.setStatus(BankBookingRequestStatus.REJECTED);
    request.setRejectReason(reason);
    request.setDecidedBy(decider);
    request.setDeciderHandle(creation.resolveHandle(decider));
    request.setDecidedAt(Instant.now());
    bankAuditService.record(
        BankAuditEventType.BOOKING_REQUEST_REJECTED,
        accountId,
        null,
        request.getRequestedBy(),
        "rejected request " + request.shortId());
    var event =
        new BankBookingRequestRejectedEvent(
            request.getId(),
            accountId,
            request.accountNo(),
            request.getAmount(),
            reason,
            request.getRequestedBy(),
            decider);
    if (linked != null) {
      notificationCauses.appendPreferred(request.getId(), event);
      linked.complete(new BankLinkedOutcome.Rejected(request.getId()));
      linkedLiveSync.changed();
    } else {
      eventPublisher.publishEvent(event);
      purposeObservers.notify(
          request, (observer, ref) -> observer.onRejected(request.getId(), ref));
    }
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
    request.requirePending();
    if (granted) {
      requireSecondPerson(request, staffRights());
    }
    BankAccount account = request.getAccount();
    if (account == null || (granted && !request.isRequiresOwnerApproval())) {
      throw new BadRequestException(
          "This request does not require the responsible holder's approval");
    }
    UUID actor = authHelperService.currentUserId().orElse(null);
    request.setOwnerApprovalGranted(granted);
    request.setOwnerApprovalGrantedBy(granted ? actor : null);
    request.setOwnerApprovalGrantedByHandle(granted ? creation.resolveHandle(actor) : null);
    request.setOwnerApprovalGrantedAt(granted ? Instant.now() : null);
    bankAuditService.record(
        granted
            ? BankAuditEventType.BOOKING_REQUEST_OWNER_APPROVAL_GRANTED
            : BankAuditEventType.BOOKING_REQUEST_OWNER_APPROVAL_REVOKED,
        account.getId(),
        null,
        request.getRequestedBy(),
        (granted ? "granted" : "revoked") + " owner approval for request " + request.shortId());
    eventPublisher.publishEvent(
        granted
            ? BankNotices.approved(
                request.getId(),
                account.getId(),
                request.getRequestedBy(),
                request.getType(),
                request.getAmount(),
                account.getAccountNo(),
                Objects.requireNonNull(request.getOwnerApprovalGrantedByHandle()),
                actor)
            : BankNotices.approvalRevoked(
                request.getId(),
                account.getId(),
                request.getType(),
                request.getAmount(),
                account.getAccountNo(),
                request.getRequesterHandle(),
                actor));
    if (request.getLinkedAttempt() != null) {
      linkedLiveSync.changed();
    }
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
   * Maps a page of request entities as {@link #toDto(BankBookingRequest)} does, naming every row's
   * client from one registry read.
   *
   * @param page the page of request entities
   * @return the page of wire DTOs
   */
  @NotNull
  private Page<BankBookingRequestDto> toDtos(@NotNull Page<BankBookingRequest> page) {
    Function<BankBookingRequest, String> names = clientNames.forRequests(page.getContent());
    return page.map(request -> BankBookingRequestDtos.of(request, names.apply(request), null));
  }
}

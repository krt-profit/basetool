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

import static de.greluc.krt.profit.basetool.backend.util.BankAmounts.plain;

import de.greluc.krt.profit.basetool.backend.event.BankBookingRequestConfirmedEvent;
import de.greluc.krt.profit.basetool.backend.event.BankBookingRequestCreatedEvent;
import de.greluc.krt.profit.basetool.backend.event.BankBookingRequestRejectedEvent;
import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.BankConflictException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.BankAccount;
import de.greluc.krt.profit.basetool.backend.model.BankAccountStatus;
import de.greluc.krt.profit.basetool.backend.model.BankAuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequest;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestStatus;
import de.greluc.krt.profit.basetool.backend.model.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.model.BankHolder;
import de.greluc.krt.profit.basetool.backend.model.BankRequestApprover;
import de.greluc.krt.profit.basetool.backend.model.BankTransaction;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BankBookingRequestDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BankTransactionDto;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankDepositRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankTransferRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.request.BankWithdrawalRequest;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountGrantRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankBookingRequestRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankHolderRepository;
import de.greluc.krt.profit.basetool.backend.repository.BankTransactionRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.OptimisticLock;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lifecycle engine for confirm-before-post bank booking requests (epic #666 F2,
 * REQ-BANK-022/-023). Deliberately <strong>org-unit-blind</strong>: like every {@code Bank*}-named
 * class it consults only bank roles and {@code bank_account_grant} rows (REQ-BANK-008, never {@code
 * OwnerScopeService}). The org-unit authorization for the requester side lives one layer up in
 * {@link OrgUnitBankAccessService}, which resolves the caller's overseen account and then delegates
 * the actual persistence here.
 *
 * <p><strong>Off-ledger, then booked.</strong> A request is a mutable aggregate (ADR-0021) and
 * moves no money while {@code PENDING}; it is audited on creation. Only {@link #confirm} books
 * value, by reusing the existing {@link BankLedgerService} path — which gives the request the same
 * account-locking, holder-at-confirmation, holder-activity and overdraft-at-confirmation guards as
 * a direct deposit/withdrawal for free (REQ-BANK-006). Every decision path locks the request row
 * first ({@code findByIdForUpdate}) so two decisions serialize, and the request's {@code @Version}
 * guards against a stale double-decision; the request is mutated in place and flushed by dirty
 * checking — no explicit {@code save} that would risk a second version bump (the {@code
 * …WithinTransaction} discipline of CLAUDE.md).
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
  private final BankAccountGrantRepository grantRepository;
  private final BankLedgerService bankLedgerService;
  private final BankSecurityService bankSecurityService;
  private final BankAuditService bankAuditService;
  private final AuthHelperService authHelperService;
  private final UserRepository userRepository;
  private final OrgUnitMembershipService orgUnitMembershipService;
  private final ApplicationEventPublisher eventPublisher;

  /**
   * Persists a new {@code PENDING} booking request against the given account, audits it and fires
   * the notification event (REQ-BANK-022/-026/-040/-041). Called by {@link
   * OrgUnitBankAccessService} after it has verified the caller may view the source account and
   * resolved the org-unit-aware approval snapshot — this method itself only enforces the
   * bank-domain rules (closed account, valid + active transfer destination, no self-transfer).
   *
   * @param accountId the source account (already view-checked by the caller)
   * @param type deposit, withdrawal or transfer
   * @param amount the requested whole-aUEC amount
   * @param note the requester's optional note
   * @param justification the requester's optional justification (Begr&uuml;ndung); required for a
   *     {@code WITHDRAWAL} / {@code TRANSFER} from a {@linkplain
   *     de.greluc.krt.profit.basetool.backend.model.BankAccountType#requiresDebitJustification()
   *     justification-mandating} account (REQ-BANK-045)
   * @param targetAccountId the transfer destination, or {@code null} for deposit/withdrawal
   * @param requiresOwnerApproval whether the amount exceeds the requester's approval limit
   *     (snapshot)
   * @param applicableLimit the requester's resolved approval limit (snapshot), or {@code null}
   * @param requiredApprover which approver class a flagged request needs (REQ-BANK-041/-046
   *     snapshot), or {@code null} when it needs no approval; opaque to this org-unit-blind service
   *     (the seam resolves the band&rarr;identity mapping)
   * @param splitEnabled whether a deposit distributes a percentage across the squadron accounts on
   *     confirmation (REQ-BANK-044); always {@code false} for withdrawal/transfer
   * @param splitPercent the whole-percent (1–100) of the deposit gross to distribute; {@code null}
   *     unless {@code splitEnabled}
   * @return the created request
   * @throws NotFoundException when the (source or destination) account does not exist
   * @throws BankConflictException with {@code BANK_ACCOUNT_CLOSED} on a closed account or {@code
   *     BANK_SELF_TRANSFER} when source equals destination
   */
  @Transactional
  public BankBookingRequestDto create(
      @NotNull UUID accountId,
      @NotNull BankBookingRequestType type,
      @NotNull BigDecimal amount,
      String note,
      @Nullable String justification,
      @Nullable UUID targetAccountId,
      boolean requiresOwnerApproval,
      @Nullable BigDecimal applicableLimit,
      @Nullable BankRequestApprover requiredApprover,
      boolean splitEnabled,
      @Nullable BigDecimal splitPercent) {
    BankAccount account =
        accountRepository
            .findById(accountId)
            .orElseThrow(() -> new NotFoundException("Bank account not found"));
    requireActiveForRequest(account);
    // REQ-BANK-045: a withdrawal/transfer request leaving a justification-mandating account
    // (CARTEL, CARTEL_BANK, SPECIAL) must carry a non-blank Begründung; a deposit never does.
    if (type == BankBookingRequestType.WITHDRAWAL || type == BankBookingRequestType.TRANSFER) {
      BankLedgerService.requireDebitJustification(account, justification);
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
          accountRepository
              .findById(targetAccountId)
              .orElseThrow(() -> new NotFoundException("Destination account not found"));
      requireActiveForRequest(targetAccount);
    }

    UUID requesterSub = authHelperService.currentUserId().orElse(null);
    String requesterHandle = resolveHandle(requesterSub);

    BankBookingRequest request = new BankBookingRequest();
    request.setAccount(account);
    request.setTargetAccount(targetAccount);
    request.setType(type);
    request.setAmount(amount);
    request.setNote(note);
    request.setJustification(justification);
    request.setStatus(BankBookingRequestStatus.PENDING);
    request.setRequestedBy(requesterSub);
    request.setRequesterHandle(requesterHandle);
    request.setRequiresOwnerApproval(requiresOwnerApproval);
    request.setApplicableLimit(applicableLimit);
    request.setRequiredApprover(requiredApprover);
    request.setSplitEnabled(splitEnabled);
    request.setSplitPercent(splitPercent);
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
   * Lists the current caller's own booking requests, newest first (REQ-BANK-022). Per-user
   * isolation: it reads strictly the caller's {@code sub}, so it can never surface a foreign
   * request.
   *
   * @return the caller's requests; never {@code null}, empty when unauthenticated or none exist
   */
  @NotNull
  public List<BankBookingRequestDto> listForCurrentRequester() {
    return authHelperService
        .currentUserId()
        .map(
            sub ->
                requestRepository.findByRequestedByOrderByCreatedAtDesc(sub).stream()
                    .map(this::toDto)
                    .toList())
        .orElseGet(List::of);
  }

  /**
   * Lists every request on the given accounts, newest first (REQ-BANK-041) — backs the responsible
   * holder's "Fremde Anträge" tab. Org-unit-blind: the caller ({@link OrgUnitBankAccessService})
   * supplies the set of accounts the holder is responsible for.
   *
   * @param accountIds the accounts to list requests for
   * @return the requests on those accounts, newest first; empty when the set is empty
   */
  @NotNull
  public List<BankBookingRequestDto> listForAccounts(@NotNull Collection<UUID> accountIds) {
    if (accountIds.isEmpty()) {
      return List.of();
    }
    return requestRepository.findByAccountIdInOrderByCreatedAtDesc(accountIds).stream()
        .map(this::toDto)
        .toList();
  }

  /**
   * Cancels the caller's own pending request (REQ-BANK-022). A request that does not belong to the
   * caller is reported as not found (per-user isolation never reveals foreign requests); a request
   * that is no longer pending yields a 409.
   *
   * @param requestId the request to cancel
   * @param version the echoed optimistic-locking version
   * @return the cancelled request
   * @throws NotFoundException when the request does not exist or belongs to another user
   * @throws BankConflictException with {@code BANK_REQUEST_NOT_PENDING} when already decided
   * @throws ObjectOptimisticLockingFailureException on a version mismatch (409)
   */
  @Transactional
  public BankBookingRequestDto cancelOwn(@NotNull UUID requestId, long version) {
    BankBookingRequest request = lockRequest(requestId);
    UUID caller = authHelperService.currentUserId().orElse(null);
    if (caller == null || !caller.equals(request.getRequestedBy())) {
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
        caller,
        "cancelled request " + shortId(request.getId()));
    return toDto(request);
  }

  /**
   * One page of booking requests in the given lifecycle state for the bank-staff confirmation queue
   * (REQ-BANK-023). Management sees every account; an employee sees only requests on accounts they
   * are granted on. This is a pure bank-staff surface — it consults grants, never org-unit scope.
   *
   * @param status the lifecycle state to list (e.g. {@code PENDING})
   * @param pageable page, size and whitelisted sort
   * @return one page of requests visible to the caller
   */
  @NotNull
  public Page<BankBookingRequestDto> listQueue(
      @NotNull BankBookingRequestStatus status, @NotNull Pageable pageable) {
    if (bankSecurityService.isManagement()) {
      return requestRepository.findByStatus(status, pageable).map(this::toDto);
    }
    Optional<UUID> caller = authHelperService.currentUserId();
    if (caller.isEmpty()) {
      return Page.empty(pageable);
    }
    Set<UUID> grantedAccountIds =
        grantRepository.findByUserId(caller.get()).stream()
            .map(grant -> grant.getId().getAccountId())
            .collect(Collectors.toSet());
    if (grantedAccountIds.isEmpty()) {
      return Page.empty(pageable);
    }
    return requestRepository
        .findByStatusAndAccountIdIn(status, grantedAccountIds, pageable)
        .map(this::toDto);
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
   * @param ownerApprovalConfirmed the over-limit "approval by responsible holder obtained"
   *     attestation (REQ-BANK-041); required when the request needs approval
   * @param version the echoed optimistic-locking version
   * @param authentication the current authentication (capability check)
   * @return the confirmed request
   * @throws NotFoundException when the request does not exist
   * @throws AccessDeniedException when the employee lacks the matching per-account capability
   * @throws BankConflictException with {@code BANK_REQUEST_NOT_PENDING}, {@code
   *     BANK_OWNER_APPROVAL_REQUIRED}, or a ledger conflict ({@code BANK_ACCOUNT_CLOSED}, {@code
   *     BANK_OVERDRAFT}, {@code BANK_SELF_TRANSFER}); the holder may go negative (REQ-BANK-006,
   *     ADR-0039)
   * @throws ObjectOptimisticLockingFailureException on a version mismatch (409)
   */
  @Transactional
  public BankBookingRequestDto confirm(
      @NotNull UUID requestId,
      @NotNull UUID holderId,
      @Nullable UUID destinationHolderId,
      boolean ownerApprovalConfirmed,
      long version,
      Authentication authentication) {
    BankBookingRequest request = lockRequest(requestId);
    requireVersion(request, version);
    requirePending(request);
    // REQ-BANK-041: an over-limit request needs the bank employee to attest the responsible
    // holder's approval was obtained before any money moves.
    if (request.isRequiresOwnerApproval() && !ownerApprovalConfirmed) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_OWNER_APPROVAL_REQUIRED,
          "The request exceeds the requester's approval limit; confirm the responsible holder's"
              + " approval first");
    }
    UUID accountId = request.getAccount().getId();
    requireConfirmCapability(request.getType(), accountId, authentication);

    // REQ-BANK-044: a confirmed deposit/withdrawal records the requester as the counterparty
    // (Einzahler/Empfänger — for a deposit request, REQ-BANK-042, the requester IS the depositor)
    // together with their org unit. requested_by is ON DELETE SET NULL, so a non-null id always
    // still resolves; the requester may belong to several units, so the deterministic primary unit
    // is recorded (name-sorted primary Staffel, or a leader's Bereich/OL), null when they have
    // none.
    UUID requesterId = request.getRequestedBy();
    UUID counterpartyOrgUnitId =
        requesterId == null
            ? null
            : orgUnitMembershipService
                .findPrimaryDirectMembershipOrgUnitId(requesterId)
                .orElse(null);

    BankTransactionDto booked =
        switch (request.getType()) {
          case DEPOSIT ->
              // REQ-BANK-043: a split deposit request carries the snapshotted percentage; the
              // concrete per-squadron legs are resolved by bookDeposit against the squadron
              // accounts
              // active NOW (at confirmation), not at request time.
              bankLedgerService.bookDeposit(
                  new BankDepositRequest(
                      accountId,
                      holderId,
                      request.getAmount(),
                      request.getNote(),
                      request.isSplitEnabled(),
                      request.getSplitPercent(),
                      requesterId,
                      counterpartyOrgUnitId));
          case WITHDRAWAL ->
              bankLedgerService.bookWithdrawal(
                  new BankWithdrawalRequest(
                      accountId,
                      holderId,
                      request.getAmount(),
                      request.getNote(),
                      request.getJustification(),
                      requesterId,
                      counterpartyOrgUnitId));
          case TRANSFER -> {
            BankAccount target = request.getTargetAccount();
            if (target == null || destinationHolderId == null) {
              throw new NotFoundException(
                  "A transfer confirmation requires the destination account and holder");
            }
            // REQ-BANK-040: confirming a transfer *request* is not a direct employee-initiated
            // transfer. The requester (an authorized source-account viewer) already chose the
            // destination from any active account, and requireConfirmCapability above already
            // checked the employee's can_transfer on the SOURCE. The direct-transfer
            // destination-visibility gate (REQ-BANK-011) therefore does not apply here, so a
            // scoped employee without a grant on the destination can still execute the request
            // (passing destinationVisible = true) instead of hitting a permanent dead-end.
            yield bankLedgerService.bookTransfer(
                new BankTransferRequest(
                    accountId,
                    holderId,
                    target.getId(),
                    destinationHolderId,
                    request.getAmount(),
                    request.getNote(),
                    request.getJustification()),
                true);
          }
        };

    BankHolder holder =
        holderRepository
            .findById(holderId)
            .orElseThrow(() -> new NotFoundException("Bank holder not found"));
    BankTransaction transaction =
        transactionRepository
            .findById(booked.id())
            .orElseThrow(() -> new NotFoundException("Bank transaction not found"));
    UUID decider = authHelperService.currentUserId().orElse(null);
    request.setHolder(holder);
    request.setResultingTransaction(transaction);
    request.setStatus(BankBookingRequestStatus.CONFIRMED);
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
    // REQ-BANK-041: record the employee's over-limit approval attestation as its own audit event.
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
   * @param authentication the current authentication (visibility check)
   * @return the rejected request
   * @throws NotFoundException when the request does not exist
   * @throws AccessDeniedException when the employee may not see the account
   * @throws BankConflictException with {@code BANK_REQUEST_NOT_PENDING} when already decided
   * @throws ObjectOptimisticLockingFailureException on a version mismatch (409)
   */
  @Transactional
  public BankBookingRequestDto reject(
      @NotNull UUID requestId,
      @NotNull String reason,
      long version,
      Authentication authentication) {
    BankBookingRequest request = lockRequest(requestId);
    requireVersion(request, version);
    requirePending(request);
    UUID accountId = request.getAccount().getId();
    if (!bankSecurityService.canSee(accountId, authentication)) {
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
    return toDto(request);
  }

  /**
   * Applies (grants or revokes) the responsible holder's in-app approval on an already-loaded,
   * locked request (REQ-BANK-041). Org-unit-blind: the org-unit authorization (the caller is the
   * account's responsible holder) is enforced by {@link OrgUnitBankAccessService} before this is
   * called; here we only enforce the bank-domain rules (the request is still pending and, for a
   * grant, actually needs approval), mutate in place (dirty-checking, no {@code save} that would
   * double-bump {@code @Version}) and audit. {@code MANDATORY} so it always runs inside the seam's
   * transaction.
   *
   * @param request the locked, managed request
   * @param granted whether to grant ({@code true}) or revoke ({@code false}) the approval
   * @return the updated request
   * @throws BadRequestException when granting approval to a request that needs none
   * @throws BankConflictException with {@code BANK_REQUEST_NOT_PENDING} when no longer pending
   */
  @NotNull
  @Transactional(propagation = Propagation.MANDATORY)
  public BankBookingRequestDto applyOwnerApprovalWithinTransaction(
      @NotNull BankBookingRequest request, boolean granted) {
    requirePending(request);
    if (granted && !request.isRequiresOwnerApproval()) {
      throw new BadRequestException(
          "This request does not require the responsible holder's approval");
    }
    UUID actor = authHelperService.currentUserId().orElse(null);
    if (granted) {
      request.setOwnerApprovalGranted(true);
      request.setOwnerApprovalGrantedBy(actor);
      request.setOwnerApprovalGrantedByHandle(resolveHandle(actor));
      request.setOwnerApprovalGrantedAt(Instant.now());
    } else {
      request.setOwnerApprovalGranted(false);
      request.setOwnerApprovalGrantedBy(null);
      request.setOwnerApprovalGrantedByHandle(null);
      request.setOwnerApprovalGrantedAt(null);
    }
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
   * Enforces that the confirming employee holds the per-account capability matching the request
   * type: {@code can_deposit} for a deposit, {@code can_withdraw} for a withdrawal (REQ-BANK-023).
   *
   * @param type the request type
   * @param accountId the target account
   * @param authentication the current authentication
   * @throws AccessDeniedException when the capability is missing
   */
  private void requireConfirmCapability(
      @NotNull BankBookingRequestType type,
      @NotNull UUID accountId,
      Authentication authentication) {
    boolean allowed =
        switch (type) {
          case DEPOSIT -> bankSecurityService.canDeposit(accountId, authentication);
          case WITHDRAWAL -> bankSecurityService.canWithdraw(accountId, authentication);
          case TRANSFER -> bankSecurityService.canTransfer(accountId, authentication);
        };
    if (!allowed) {
      throw new AccessDeniedException(
          "The caller lacks the bank capability to confirm this request");
    }
  }

  /**
   * Loads and pessimistically locks a request for a decision, or fails with 404.
   *
   * @param requestId the request id
   * @return the locked, managed request
   * @throws NotFoundException when the request does not exist
   */
  private BankBookingRequest lockRequest(@NotNull UUID requestId) {
    return requestRepository
        .findByIdForUpdate(requestId)
        .orElseThrow(() -> new NotFoundException("Booking request not found"));
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
  private String resolveHandle(UUID userId) {
    return Optional.ofNullable(userId)
        .flatMap(userRepository::findById)
        .map(User::getEffectiveName)
        .orElse("unknown");
  }

  /**
   * Maps a request entity to its wire shape, resolving the owning org unit, recorded holder and
   * resulting transaction (all eagerly fetched on the list/queue reads, lazily resolvable within
   * the single-row transactional paths).
   *
   * @param request the request entity
   * @return the wire DTO
   */
  private BankBookingRequestDto toDto(@NotNull BankBookingRequest request) {
    BankAccount account = request.getAccount();
    OrgUnit orgUnit = account.getOrgUnit();
    BankHolder holder = request.getHolder();
    BankTransaction transaction = request.getResultingTransaction();
    BankAccount target = request.getTargetAccount();
    return new BankBookingRequestDto(
        request.getId(),
        account.getId(),
        account.getAccountNo(),
        account.getName(),
        orgUnit == null ? null : orgUnit.getId(),
        orgUnit == null ? null : orgUnit.getName(),
        orgUnit == null ? null : orgUnit.getShorthand(),
        request.getType(),
        request.getAmount(),
        request.getNote(),
        request.getJustification(),
        request.getStatus(),
        request.getRequesterHandle(),
        holder == null ? null : holder.getId(),
        holder == null ? null : holder.getDisplayName(),
        transaction == null ? null : transaction.getId(),
        request.getDeciderHandle(),
        request.getRejectReason(),
        request.getDecidedAt(),
        request.getCreatedAt(),
        target == null ? null : target.getId(),
        target == null ? null : target.getAccountNo(),
        request.isRequiresOwnerApproval(),
        request.getApplicableLimit(),
        request.getRequiredApprover() == null ? null : request.getRequiredApprover().name(),
        request.isOwnerApprovalGranted(),
        request.getOwnerApprovalGrantedByHandle(),
        request.isSplitEnabled(),
        request.getSplitPercent(),
        request.getVersion());
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

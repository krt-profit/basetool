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

import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.api.BankAdmission;
import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurpose;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.Entities;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The booking-request side of the org-unit bank page (REQ-BANK-039…042, REQ-BANK-046,
 * REQ-BANK-047): raising, correcting and cancelling requests, the approval routing, and the
 * approver's queue and attestation.
 *
 * <p>Every decision comes from {@link BankAccessRules} for the actor of {@link
 * OrgUnitBankAccessService} (ADR-0020, ADR-0352); persistence and audit stay in the org-unit-blind
 * {@link BankBookingRequestService}.
 */
@Service
@RequiredArgsConstructor
public class OrgUnitBankRequestService {

  private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt");

  private final OrgUnitBankAccessService accessService;
  private final BankAccessRules policy;
  private final BankAccountRepository bankAccountRepository;
  private final BankBookingRequestRepository bankBookingRequestRepository;
  private final BankBookingRequestService bankBookingRequestService;

  /**
   * Raises a booking request and delegates persistence to {@link BankBookingRequestService}.
   *
   * <p>A deposit may be requested by anyone against any active account and needs no approval
   * (REQ-BANK-042). A withdrawal or transfer is admitted and routed by {@link
   * BankAccessRules#admit} (REQ-BANK-039).
   *
   * @param request the create payload
   * @return the created pending request
   * @throws AccessDeniedException when a withdrawal or transfer caller may not view the account
   * @throws BadRequestException when the destination account is missing or superfluous for the
   *     type, or the account accepts no withdrawals or transfers
   * @throws NotFoundException when the source account does not exist
   */
  @NotNull
  @Transactional
  public BankBookingRequestDto createBookingRequest(@NotNull CreateBankBookingRequest request) {
    BankAccount account =
        Entities.require(
            bankAccountRepository.findById(request.sourceAccountId()), "Bank account not found");

    if (request.type() == BankBookingRequestType.DEPOSIT) {
      if (request.targetAccountId() != null) {
        throw new BadRequestException("A deposit request must not carry a destination account");
      }
      return bankBookingRequestService.create(
          account.getId(),
          BankBookingRequestType.DEPOSIT,
          request.amount(),
          request.note(),
          null,
          null,
          false,
          null,
          null,
          request.splitEnabled(),
          request.splitPercent(),
          null,
          null);
    }

    BankAdmission.Admitted routing =
        switch (policy.admit(
            accessService.actor(),
            account,
            accessService.pin(),
            request.type(),
            request.amount())) {
          case BankAdmission.Admitted admitted -> admitted;
          case BankAdmission.Rejection.NOT_FOUND ->
              throw new AccessDeniedException(
                  "The caller may not raise a booking request on this account");
          case BankAdmission.Rejection.NOT_REQUEST_CAPABLE ->
              throw new BadRequestException("This account does not accept booking requests");
        };
    UUID targetAccountId = null;
    if (request.type() == BankBookingRequestType.TRANSFER) {
      if (request.targetAccountId() == null) {
        throw new BadRequestException("A transfer request requires a destination account");
      }
      targetAccountId = request.targetAccountId();
    } else if (request.targetAccountId() != null) {
      throw new BadRequestException("A non-transfer request must not carry a destination account");
    }
    return bankBookingRequestService.create(
        account.getId(),
        request.type(),
        request.amount(),
        request.note(),
        request.justification(),
        targetAccountId,
        routing.requiresApproval(),
        routing.applicableLimit(),
        routing.requiredApprover(),
        false,
        null,
        request.counterpartyUserId(),
        request.counterpartyOrgUnitId());
  }

  /**
   * Raises a booking request for another module's purpose (REQ-BANK-061) on a funding account: a
   * {@code WITHDRAWAL} from a {@code CARTEL} account is routed through the KRT ladder like any
   * request, a withdrawal from a {@code SPECIAL} account and every deposit need no owner approval,
   * and the bank's confirmation (with four-eyes, REQ-BANK-062) always follows. The caller is the
   * requester.
   *
   * @param accountId the active {@code CARTEL} or {@code SPECIAL} account
   * @param type {@code DEPOSIT} or {@code WITHDRAWAL}
   * @param amount the whole-aUEC amount
   * @param justification the Begr&uuml;ndung of a withdrawal
   * @param counterpartyUserId the Empf&auml;nger of a withdrawal, or {@code null}
   * @param purpose what the request is for
   * @return the pending request
   * @throws NotFoundException when the account does not exist
   * @throws BadRequestException when the account is closed or not a funding account type, or the
   *     type is neither deposit nor withdrawal
   */
  @NotNull
  @Transactional(propagation = Propagation.MANDATORY)
  public BankBookingRequestDto raisePurposeBoundRequest(
      @NotNull UUID accountId,
      @NotNull BankBookingRequestType type,
      @NotNull BigDecimal amount,
      @Nullable String justification,
      @Nullable UUID counterpartyUserId,
      @NotNull BankPurpose purpose) {
    BankAccount account =
        Entities.require(bankAccountRepository.findById(accountId), "Bank account not found");
    if (account.getStatus() != BankAccountStatus.ACTIVE
        || (account.getType() != BankAccountType.CARTEL
            && account.getType() != BankAccountType.SPECIAL)) {
      throw new BadRequestException(
          "A purpose-bound request needs an active KRT or special account");
    }
    BankAdmission.Admitted routing =
        switch (type) {
          case WITHDRAWAL ->
              account.getType() == BankAccountType.CARTEL
                  ? BankAccessRules.cartelLadder(account, amount)
                  : BankAdmission.Admitted.UNROUTED;
          case DEPOSIT -> BankAdmission.Admitted.UNROUTED;
          case TRANSFER ->
              throw new BadRequestException("A purpose-bound request is a deposit or a withdrawal");
        };
    return bankBookingRequestService.create(
        accountId,
        type,
        amount,
        null,
        justification,
        null,
        routing.requiresApproval(),
        routing.applicableLimit(),
        routing.requiredApprover(),
        false,
        null,
        counterpartyUserId,
        null,
        purpose);
  }

  /**
   * Files a pending, band-routed request for a bank-staff direct withdrawal or transfer that
   * exceeds the KRT employee ceiling {@code T1} (REQ-BANK-047).
   *
   * <p>Relies on the caller having passed the bank capability gate; only amount, note,
   * justification and target carry over.
   *
   * @param accountId the KRT source account
   * @param type {@code WITHDRAWAL} or {@code TRANSFER}
   * @param amount the whole-aUEC amount leaving the account
   * @param note the optional note
   * @param justification the justification
   * @param targetAccountId the destination for a {@code TRANSFER}, else {@code null}
   * @return the filed pending booking request
   * @throws NotFoundException when the account does not exist
   */
  @NotNull
  @Transactional
  public BankBookingRequestDto raiseCartelDirectBookingRequest(
      @NotNull UUID accountId,
      @NotNull BankBookingRequestType type,
      @NotNull BigDecimal amount,
      @Nullable String note,
      @Nullable String justification,
      @Nullable UUID targetAccountId) {
    BankAccount account =
        Entities.require(bankAccountRepository.findById(accountId), "Bank account not found");
    BankAdmission.Admitted routing =
        policy.routeCartelDirectBooking(accessService.actor(), account, amount);
    return bankBookingRequestService.create(
        accountId,
        type,
        amount,
        note,
        justification,
        targetAccountId,
        routing.requiresApproval(),
        routing.applicableLimit(),
        routing.requiredApprover(),
        false,
        null,
        null,
        null);
  }

  /**
   * Applies a requester's correction to their own pending booking request and re-derives the
   * approval snapshot (REQ-BANK-056).
   *
   * <p>{@link BankBookingRequestService#updateOwn} re-checks ownership, state and version under the
   * row lock.
   *
   * @param requestId the request to correct
   * @param request the corrected values and the echoed version
   * @return the updated request
   * @throws NotFoundException when the request does not exist or belongs to another user
   */
  @NotNull
  @Transactional
  public BankBookingRequestDto updateOwnBookingRequest(
      @NotNull UUID requestId, @NotNull UpdateBankBookingRequest request) {
    BankBookingRequest existing =
        Entities.require(
            bankBookingRequestRepository.findById(requestId), "Booking request not found");
    BankActor actor = accessService.actor();
    if (!policy.ownsRequest(actor, existing)) {
      throw new NotFoundException("Booking request not found");
    }
    BankAdmission.Admitted routing = policy.route(actor, existing.getAccount(), request.amount());
    return bankBookingRequestService.updateOwn(
        requestId,
        request,
        routing.requiresApproval(),
        routing.applicableLimit(),
        routing.requiredApprover());
  }

  /**
   * Lists the caller's own booking requests with the staff note removed (REQ-BANK-022,
   * REQ-BANK-054).
   *
   * @return the caller's requests, newest first
   */
  @NotNull
  public List<BankBookingRequestDto> listOwnBookingRequests() {
    return pageOwnBookingRequests(Set.of(), Pageable.unpaged(NEWEST_FIRST)).getContent();
  }

  /**
   * Pages the caller's own booking requests in the given states with the staff note removed
   * (REQ-BANK-022, REQ-BANK-054); the dashboard's "Meine Bankanträge" widget reads its rows and
   * total from it.
   *
   * @param statuses the lifecycle states to include; empty means every state
   * @param pageable page, size and sort
   * @return the caller's requests of that page, with the total over all pages
   */
  @NotNull
  public Page<BankBookingRequestDto> pageOwnBookingRequests(
      @NotNull Set<BankBookingRequestStatus> statuses, @NotNull Pageable pageable) {
    Set<BankBookingRequestStatus> effective =
        statuses.isEmpty() ? EnumSet.allOf(BankBookingRequestStatus.class) : statuses;
    return bankBookingRequestService
        .pageForCurrentRequester(effective, pageable)
        .map(BankBookingRequestDto::withoutStaffNote);
  }

  /**
   * Cancels the caller's own pending booking request (REQ-BANK-022).
   *
   * @param requestId the request to cancel
   * @param version the echoed optimistic-locking version
   * @return the cancelled request
   */
  @NotNull
  public BankBookingRequestDto cancelOwnBookingRequest(@NotNull UUID requestId, long version) {
    return bankBookingRequestService.cancelOwn(requestId, version);
  }

  /**
   * Lists every active account as a transfer-request destination (REQ-BANK-040): a requester may
   * transfer from a viewable source account to <em>any</em> active account. Balance-free references
   * only; ordered by account number.
   *
   * @return the active accounts as transfer targets; never {@code null}
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<BankAccountRefDto> listTransferTargetAccounts() {
    return bankAccountRepository.findAllByOrderByAccountNoAsc().stream()
        .filter(account -> account.getStatus() == BankAccountStatus.ACTIVE)
        .map(account ->
            new BankAccountRefDto(
                account.getId(), account.getAccountNo(), account.getName(), account.getType()))
        .toList();
  }

  /**
   * Lists the booking requests the caller may act on: all requests of accounts they hold, the KRT
   * band requests routed to them, or everything for an admin (REQ-BANK-046).
   *
   * @return the requests the caller may act on; empty when none
   */
  @NotNull
  @Transactional(readOnly = true)
  public List<BankBookingRequestDto> listRequestsForResponsibleAccounts() {
    return bankBookingRequestService.listForeign(policy.foreignRequestScope(accessService.actor()));
  }

  /**
   * Grants the required approver's approval of an over-limit booking request (REQ-BANK-041).
   *
   * @param requestId the request to approve
   * @return the updated request
   * @throws NotFoundException when the request does not exist
   * @throws AccessDeniedException when the caller may not approve the request
   * @throws BadRequestException when the request needs no approval or is no longer pending
   */
  @NotNull
  @Transactional
  public BankBookingRequestDto grantOwnerApproval(@NotNull UUID requestId) {
    return applyOwnerApproval(requestId, true);
  }

  /**
   * Revokes a previously granted in-app approval for a booking request (REQ-BANK-041/-046).
   *
   * @param requestId the request whose approval to revoke
   * @return the updated request
   * @throws NotFoundException when the request does not exist
   * @throws AccessDeniedException when the caller may not approve the request
   */
  @NotNull
  @Transactional
  public BankBookingRequestDto revokeOwnerApproval(@NotNull UUID requestId) {
    return applyOwnerApproval(requestId, false);
  }

  /**
   * Loads and locks a request, authorizes the caller through the policy, and delegates the mutation
   * and audit to {@link
   * BankBookingRequestService#applyOwnerApprovalWithinTransaction(BankBookingRequest, boolean)}.
   *
   * @param requestId the request
   * @param granted {@code true} to grant, {@code false} to revoke the approval
   * @return the updated request
   */
  @NotNull
  private BankBookingRequestDto applyOwnerApproval(@NotNull UUID requestId, boolean granted) {
    BankBookingRequest request =
        Entities.require(
            bankBookingRequestRepository.findByIdForUpdate(requestId), "Booking request not found");
    if (!policy.mayApprove(accessService.actor(), request)) {
      throw new AccessDeniedException("The caller may not approve this request");
    }
    return bankBookingRequestService.applyOwnerApprovalWithinTransaction(request, granted);
  }
}

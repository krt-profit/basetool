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
import de.greluc.krt.profit.basetool.backend.bank.api.BankConflictException;
import de.greluc.krt.profit.basetool.backend.bank.api.BankOwnRequestWrites;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves {@link BankOwnRequestWrites} through the web's request services (REQ-BXCH-004): the
 * payload passes the web's bean validation, the policy admits and routes for the given actor, and a
 * raised request records the connected application the gate admitted (REQ-BANK-067).
 *
 * <p>What the exchange adds is only its refusals: an unknown, closed or hidden account and another
 * member's request answer the same {@link NotFoundException}, and a member who holds {@link
 * BankExchangeProperties#openRequestCap()} pending requests is refused {@code
 * BANK_OPEN_REQUEST_CAP}.
 */
@Service
@RequiredArgsConstructor
public class BankOwnRequestWriteService implements BankOwnRequestWrites {

  private final OrgUnitBankRequestService requestService;
  private final BankBookingRequestService bookingRequestService;
  private final BankBookingRequestRepository requestRepository;
  private final BankAccountRepository accountRepository;
  private final BankAccessRules policy;
  private final BankExchangeProperties properties;
  private final Validator validator;

  @Override
  @NotNull
  @Transactional
  public Written raise(
      @NotNull BankActor actor, @NotNull NewRequest request, @NotNull String clientId) {
    CreateBankBookingRequest payload =
        valid(
            new CreateBankBookingRequest(
                request.sourceAccountId(),
                request.type(),
                request.targetAccountId(),
                request.amount(),
                request.note(),
                request.justification(),
                request.splitEnabled(),
                request.splitPercent(),
                null,
                null));
    requireBelowCap(actor);
    BankAccount account = activeAccount(request.sourceAccountId());
    if (request.targetAccountId() != null) {
      activeAccount(request.targetAccountId());
    }
    BankBookingRequestDto raised =
        requestService.raiseForClient(
            actor, account, payload, BankOwnRequestWriteService::notFound, clientId);
    return written(raised.id());
  }

  @Override
  @NotNull
  @Transactional
  public Written correct(
      @NotNull BankActor actor, @NotNull UUID requestId, @NotNull Correction correction) {
    BankBookingRequest existing = ownRequest(actor, requestId);
    UpdateBankBookingRequest payload =
        valid(
            new UpdateBankBookingRequest(
                correction.amount(),
                correction.note(),
                correction.justification(),
                correction.targetAccountId(),
                existing.getCounterpartyUserId(),
                existing.getCounterpartyOrgUnitId(),
                correction.version()));
    if (correction.targetAccountId() != null) {
      activeAccount(correction.targetAccountId());
    }
    requestService.updateOwnBookingRequest(actor, requestId, payload);
    return written(requestId);
  }

  @Override
  @NotNull
  @Transactional
  public Written cancel(@NotNull BankActor actor, @NotNull UUID requestId, long version) {
    ownRequest(actor, requestId);
    bookingRequestService.cancelOwn(actor, requestId, version);
    return written(requestId);
  }

  /**
   * Refuses a request once the member holds the cap of pending requests, whichever channel raised
   * them. The member's cap lock, held until the raise's transaction ends, makes a concurrent raise
   * count only after this one committed or rolled back.
   *
   * @param actor the requester
   * @throws BankConflictException {@code BANK_OPEN_REQUEST_CAP} at or over the cap
   */
  private void requireBelowCap(@NotNull BankActor actor) {
    UUID requester = Objects.requireNonNull(actor.userId(), "An exchange actor names its member");
    requestRepository.lockOpenRequestCap(requester);
    if (requestRepository.countByRequestedByAndStatus(requester, BankBookingRequestStatus.PENDING)
        >= properties.openRequestCap()) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_OPEN_REQUEST_CAP,
          "The member holds the most pending booking requests a client may add to");
    }
  }

  /**
   * Loads an active account.
   *
   * @param accountId the account
   * @return the account
   * @throws NotFoundException when it does not exist or is closed
   */
  @NotNull
  private BankAccount activeAccount(@NotNull UUID accountId) {
    return accountRepository
        .findById(accountId)
        .filter(account -> account.getStatus() == BankAccountStatus.ACTIVE)
        .orElseThrow(BankOwnRequestWriteService::notFound);
  }

  /**
   * Loads one of the actor's own requests.
   *
   * @param actor the requester
   * @param requestId the request
   * @return the request
   * @throws NotFoundException when it does not exist or belongs to another member
   */
  @NotNull
  private BankBookingRequest ownRequest(@NotNull BankActor actor, @NotNull UUID requestId) {
    return requestRepository
        .findById(requestId)
        .filter(request -> policy.ownsRequest(actor, request))
        .orElseThrow(BankOwnRequestWriteService::notFound);
  }

  /**
   * Checks a payload against the web's bean validation.
   *
   * @param payload the payload
   * @param <T> its type
   * @return the payload
   * @throws ConstraintViolationException when a rule is broken
   */
  @NotNull
  private <T> T valid(@NotNull T payload) {
    Set<ConstraintViolation<T>> violations = validator.validate(payload);
    if (!violations.isEmpty()) {
      throw new ConstraintViolationException(violations);
    }
    return payload;
  }

  /**
   * Reads a request after its write, with the version the write produced.
   *
   * @param requestId the request
   * @return its terse state
   */
  @NotNull
  private Written written(@NotNull UUID requestId) {
    requestRepository.flush();
    BankBookingRequest request = requestRepository.findById(requestId).orElseThrow();
    return new Written(
        request.getId(),
        request.getStatus().name(),
        Objects.requireNonNullElse(request.getVersion(), 0L),
        request.getAmount());
  }

  /**
   * The shared refusal of an unknown, closed or hidden account and of another member's request.
   *
   * @return the exception
   */
  @NotNull
  private static NotFoundException notFound() {
    return new NotFoundException("The resource is unknown or hidden from the member.");
  }
}

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

import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedOutcome;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;

/** Executes normalized linked terms through the Bank ledger or debit-equivalent authority. */
@Service
@RequiredArgsConstructor
class BankLinkedBookings {
  private final BankLedgerService ledger;
  private final BankPostingWriter writer;
  private final BankDirectBookingNotifier notifier;
  private final BankAuditService audit;
  private final BankLinkedAttemptRepository attempts;

  /**
   * Acquires posting or coverage accounts before the final confirmation authority check.
   *
   * @param request the locked linked request
   */
  void lockConfirmationAccounts(@NotNull BankBookingRequest request) {
    if (request.getType() == BankBookingRequestType.HOLDER_TRANSFER) {
      ledger.lockHolderTransferFeeAccount(Objects.requireNonNull(request.getLinkedFee()));
      return;
    }
    Stream.of(request.getAccount(), request.getTargetAccount())
        .filter(Objects::nonNull)
        .map(BankAccount::getId)
        .sorted(BankPostingWriter.ACCOUNT_ID_ORDER)
        .forEach(writer::lockAccount);
  }

  /**
   * Executes the submitted terms with the custodians displayed on the request.
   *
   * @param request the locked request
   * @param holderId the echoed source holder
   * @param destinationHolderId the echoed destination holder
   * @param staffNote the private staff note
   * @param actor the authenticated confirming employee
   * @return the confirmed linked effect
   */
  @NotNull
  BankLinkedOutcome confirm(
      @NotNull BankBookingRequest request,
      @NotNull UUID holderId,
      @Nullable UUID destinationHolderId,
      @Nullable String staffNote,
      @NotNull UUID actor) {
    BankHolder holder = Objects.requireNonNull(request.getRequestedHolder());
    BankHolder destination = request.getRequestedTargetHolder();
    if (!holder.getId().equals(holderId)
        || !Objects.equals(destination == null ? null : destination.getId(), destinationHolderId)) {
      throw new BadRequestException("Confirmation must use the submitted Bank custodians");
    }
    CounterpartySnapshot counterparty = linkedCounterparty(request);
    BankLinkedBooking terms =
        new BankLinkedBooking(
            request.getType(),
            request.getAccount(),
            request.getTargetAccount(),
            holder,
            destination,
            counterparty,
            request.getAmount(),
            request.getNote(),
            request.getJustification());
    return execute(
        Objects.requireNonNull(request.getLinkedAttempt()), terms, actor, staffNote, false);
  }

  /**
   * Revalidates the frozen counterparty without substituting the applicant or another unit.
   *
   * @param request the linked request
   * @return the frozen actual party, if required
   */
  @Nullable
  private CounterpartySnapshot linkedCounterparty(@NotNull BankBookingRequest request) {
    String handle = request.getCounterpartyHandle();
    if (handle == null) {
      return null;
    }
    UUID member = request.getCounterpartyUserId();
    UUID unit = request.getCounterpartyOrgUnitId();
    if (HandleAnonymisation.isAnonymised(handle)
        || (unit == null && request.getCounterpartyOrgUnitName() != null)) {
      throw new BadRequestException("The submitted Bank counterparty is no longer available");
    }
    ledger.resolveCounterparty(member, member == null ? handle : null, unit);
    return new CounterpartySnapshot(member, handle, unit, request.getCounterpartyOrgUnitName());
  }

  /**
   * Books or authorises the fixed terms in the enclosing Bank decision transaction.
   *
   * @param attempt the stored linked attempt
   * @param terms the normalized Bank terms
   * @param actor the current Bank actor
   * @param staffNote the employee's private note
   * @param direct whether to publish existing direct booking notices
   * @return the final effect
   */
  @NotNull
  BankLinkedOutcome execute(
      @NotNull BankLinkedAttemptRecord attempt,
      @NotNull BankLinkedBooking terms,
      @NotNull UUID actor,
      @Nullable String staffNote,
      boolean direct) {
    validateParties(terms, attempt.isSamePlayer());
    if (terms.type() == BankBookingRequestType.AUTHORISATION) {
      UUID accountId = Objects.requireNonNull(terms.account()).getId();
      ledger.authorise(accountId, terms.amount(), terms.justification());
      attempts.recordAuthorisation(
          attempt.getId(), accountId, terms.holder().getId(), terms.amount(), actor);
      audit.record(
          BankAuditEventType.FUNDS_AUTHORISED,
          accountId,
          null,
          null,
          "Authorisation " + attempt.getId() + " for " + terms.amount().toPlainString() + " aUEC");
      if (direct) {
        notifier.fundsAuthorised(
            attempt.getId(), Objects.requireNonNull(terms.account()), terms.amount());
      }
      return new BankLinkedOutcome.Authorised(attempt.getId());
    }
    BankTransactionDto transaction = post(attempt, terms, staffNote, direct);
    attempt.setTransactionId(transaction.id());
    return new BankLinkedOutcome.Posted(transaction.id());
  }

  /**
   * Checks that frozen custody and any same-player classification remain valid.
   *
   * @param terms the normalized Bank terms
   * @param samePlayer whether the payment is a same-player purpose change
   */
  void validateParties(@NotNull BankLinkedBooking terms, boolean samePlayer) {
    UUID holderUserId = registeredUserId(terms.holder());
    if (terms.targetHolder() != null) {
      UUID targetUserId = registeredUserId(terms.targetHolder());
      if (samePlayer != holderUserId.equals(targetUserId)) {
        throw new BadRequestException("The submitted game transfer does not match the custodians");
      }
    }
    CounterpartySnapshot party = terms.counterparty();
    if (terms.type() == BankBookingRequestType.WITHDRAWAL
        && samePlayer != (party != null && holderUserId.equals(party.userId()))) {
      throw new BadRequestException("The submitted game transfer does not match the payee");
    }
  }

  /**
   * Resolves the linked custodian's registered identity.
   *
   * @param holder the Bank custodian
   * @return the registered member id
   */
  @NotNull
  private static UUID registeredUserId(@NotNull BankHolder holder) {
    if (holder.getUser() == null) {
      throw new BadRequestException("Linked custody requires a registered Bank holder");
    }
    return holder.getUser().getId();
  }

  /**
   * Applies a posting using the already validated terms.
   *
   * @param attempt the frozen fee record
   * @param terms the Bank terms
   * @param staffNote the private staff note
   * @param direct whether direct booking notices apply
   * @return the resulting transaction
   */
  @NotNull
  private BankTransactionDto post(
      @NotNull BankLinkedAttemptRecord attempt,
      @NotNull BankLinkedBooking terms,
      @Nullable String staffNote,
      boolean direct) {
    UUID holderId = terms.holder().getId();
    return switch (terms.type()) {
      case DEPOSIT ->
          ledger.bookLinkedDeposit(
              new BankDepositRequest(
                  Objects.requireNonNull(terms.account()).getId(),
                  holderId,
                  terms.amount(),
                  terms.note(),
                  staffNote,
                  false,
                  null,
                  null,
                  null,
                  null),
              Objects.requireNonNull(terms.counterparty()));
      case WITHDRAWAL -> {
        CounterpartySnapshot party = Objects.requireNonNull(terms.counterparty());
        BankWithdrawalRequest request =
            new BankWithdrawalRequest(
                Objects.requireNonNull(terms.account()).getId(),
                holderId,
                terms.amount(),
                terms.note(),
                terms.justification(),
                staffNote,
                party.userId(),
                party.orgUnitId(),
                false,
                null);
        BankTransactionDto booked = ledger.bookLinkedWithdrawal(request, party, attempt.getFee());
        if (direct) {
          notifier.withdrawalBooked(request, booked, !attempt.isSamePlayer());
        }
        yield booked;
      }
      case TRANSFER -> {
        BankTransferRequest request =
            new BankTransferRequest(
                Objects.requireNonNull(terms.account()).getId(),
                holderId,
                Objects.requireNonNull(terms.target()).getId(),
                Objects.requireNonNull(terms.targetHolder()).getId(),
                terms.amount(),
                terms.note(),
                terms.justification(),
                staffNote,
                false);
        BankTransactionDto booked = ledger.bookLinkedTransfer(request, attempt.getFee());
        if (direct) {
          notifier.transferBooked(request, booked);
        }
        yield booked;
      }
      case HOLDER_TRANSFER -> {
        BankHolderTransferRequest request =
            new BankHolderTransferRequest(
                holderId,
                Objects.requireNonNull(terms.targetHolder()).getId(),
                terms.amount(),
                terms.note());
        BankTransactionDto booked =
            ledger.bookLinkedHolderTransfer(request, attempt.getFee(), staffNote);
        if (direct) {
          notifier.holderTransferBooked(request, booked);
        }
        yield booked;
      }
      case AUTHORISATION -> throw new IllegalStateException("Authorisation has no posting");
    };
  }
}

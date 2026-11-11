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

import de.greluc.krt.profit.basetool.backend.admin.api.TransferFeePolicy;
import de.greluc.krt.profit.basetool.backend.bank.api.BankActor;
import de.greluc.krt.profit.basetool.backend.bank.api.BankActors;
import de.greluc.krt.profit.basetool.backend.bank.api.BankAdmission;
import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankConflictException;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedAttempt;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedEffectHandler.Action;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedOutcome;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedPayment;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedPayment.Counterparty;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedPayment.GamePayment;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedPayments;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurposeKind;
import de.greluc.krt.profit.basetool.backend.bank.api.BankStaffRights;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Admits linked attempts using the current Bank actor and the existing Bank policy. */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
class BankLinkedPaymentsService implements BankLinkedPayments {
  private static final int MAX_AUEC_DIGITS = 15;
  private static final int MAX_NOTE_LENGTH = 500;
  private static final int MAX_COUNTERPARTY_LENGTH = 255;

  private final BankLinkedDecisions decisions;
  private final BankLinkedBookings bookings;
  private final BankBookingRequestService requests;
  private final BankRequestCreation creation;
  private final BankActors actors;
  private final BankAccessRules policy;
  private final BankPostingWriter writer;
  private final BankBookingGuards guards;
  private final BankLedgerService ledger;
  private final TransferFeePolicy feePolicy;
  private final EntityManager entityManager;
  private final BankLinkedRequestLiveSync liveSync;

  @Override
  @NotNull
  public BankLinkedOutcome submit(
      @NotNull BankLinkedAttempt attempt, @NotNull BankLinkedPayment payment) {
    if (attempt.purpose().kind() != BankPurposeKind.AUEC_ORDER) {
      throw new BadRequestException("This purpose does not use the linked Bank contract");
    }
    requireValidTerms(payment.terms());
    BankLinkedDecisions.Prepared prepared = decisions.prepare(attempt, Action.SUBMIT);
    BankActor actor = actors.of(prepared.actor());
    requirePreliminaryAdmission(actor, payment);
    BankLinkedBooking booking = normalize(payment);
    actor = actors.of(prepared.actor());
    BankStaffRights staff = policy.staffRights(actor);
    boolean samePlayer =
        switch (payment) {
          case BankLinkedPayment.Withdrawal withdrawal ->
              Objects.requireNonNull(withdrawal.gamePayment()) == GamePayment.SAME_PLAYER;
          case BankLinkedPayment.Transfer transfer ->
              Objects.requireNonNull(transfer.gamePayment()) == GamePayment.SAME_PLAYER;
          case BankLinkedPayment.Deposit _,
              BankLinkedPayment.HolderTransfer _,
              BankLinkedPayment.Authorisation _ ->
              false;
        };
    bookings.validateParties(booking, samePlayer);
    BigDecimal fee =
        switch (booking.type()) {
          case AUTHORISATION, DEPOSIT -> BigDecimal.ZERO;
          case WITHDRAWAL, TRANSFER, HOLDER_TRANSFER ->
              samePlayer
                  ? BigDecimal.ZERO
                  : feePolicy.feeOn(booking.amount(), payment.terms().rate());
        };
    BankLinkedAttemptRecord record =
        new BankLinkedAttemptRecord(attempt, payment.terms().rate().rate(), fee, samePlayer);
    BankAdmission.Admitted routing = pendingRouting(actor, staff, booking);
    if (routing == null && booking.type() == BankBookingRequestType.HOLDER_TRANSFER) {
      ledger.lockHolderTransferFeeAccount(fee);
      if (!decisions.rights().staff()) {
        throw new AccessDeniedException(
            "Direct holder transfer requires current Bank staff authority");
      }
    }
    entityManager.persist(record);
    entityManager.flush();
    BankLinkedOutcome outcome;
    if (routing == null) {
      if (booking.target() != null && !staff.canSee(booking.target().getId())) {
        throw new AccessDeniedException("Destination account is not visible to the caller");
      }
      outcome = bookings.execute(record, booking, prepared.actor(), null, true);
      liveSync.changed();
    } else {
      outcome =
          new BankLinkedOutcome.Pending(creation.createLinked(record, booking, actor, routing));
    }
    prepared.complete(outcome);
    return outcome;
  }

  /**
   * Validates the submitted amount and Bank text field limits.
   *
   * @param terms the submitted Bank terms
   */
  private static void requireValidTerms(@NotNull BankLinkedPayment.Terms terms) {
    BigDecimal amount = terms.amount().stripTrailingZeros();
    if (amount.signum() <= 0
        || amount.scale() > 0
        || amount.precision() - amount.scale() > MAX_AUEC_DIGITS) {
      throw new BadRequestException("A linked Bank payment requires a positive whole-aUEC amount");
    }
    if (tooLong(terms.note()) || tooLong(terms.justification())) {
      throw new BadRequestException("A Bank note or justification exceeds the allowed length");
    }
  }

  /**
   * Checks the Bank note field length.
   *
   * @param text the optional note or justification
   * @return whether the Bank field would overflow
   */
  private static boolean tooLong(@Nullable String text) {
    return text != null && text.length() > MAX_NOTE_LENGTH;
  }

  @Override
  public void cancel(@NotNull UUID requestId, @NotNull BankLinkedAttempt attempt) {
    requests.cancelLinked(requestId, attempt);
  }

  /**
   * Checks account admission before resolving the parties or reporting account lifecycle details.
   *
   * @param actor the authenticated Bank actor
   * @param payment the submitted linked command
   */
  private void requirePreliminaryAdmission(
      @NotNull BankActor actor, @NotNull BankLinkedPayment payment) {
    AccountSelection selection =
        switch (payment) {
          case BankLinkedPayment.Deposit deposit ->
              new AccountSelection(BankBookingRequestType.DEPOSIT, deposit.accountId(), null);
          case BankLinkedPayment.Withdrawal withdrawal ->
              new AccountSelection(BankBookingRequestType.WITHDRAWAL, withdrawal.accountId(), null);
          case BankLinkedPayment.Transfer transfer ->
              new AccountSelection(
                  BankBookingRequestType.TRANSFER,
                  transfer.sourceAccountId(),
                  transfer.destinationAccountId());
          case BankLinkedPayment.Authorisation authorisation ->
              new AccountSelection(
                  BankBookingRequestType.AUTHORISATION, authorisation.accountId(), null);
          case BankLinkedPayment.HolderTransfer _ -> null;
        };
    if (selection == null || selection.type() == BankBookingRequestType.DEPOSIT) {
      return;
    }
    BankStaffRights rights = policy.staffRights(actor);
    if (!rights.canBook(selection.type(), selection.accountId())) {
      requireAdmitted(
          policy.admit(
              actor, selection.accountId(), null, selection.type(), payment.terms().amount()));
    } else if (selection.targetId() != null
        && !rights.canSee(selection.targetId())
        && !policy.exceedsDirectBookingCeiling(
            actor, selection.accountId(), payment.terms().amount())) {
      throw new AccessDeniedException("Destination account is not visible to the caller");
    }
  }

  /**
   * Account identifiers available before Bank resolves and locks the submitted parties.
   *
   * @param type the intended Bank movement
   * @param accountId the source account
   * @param targetId an optional destination
   */
  private record AccountSelection(
      @NotNull BankBookingRequestType type, @NotNull UUID accountId, @Nullable UUID targetId) {}

  /**
   * Applies direct staff admission or ordinary request and KRT fallback routing.
   *
   * @param actor the current requester
   * @param staff the current staff grants
   * @param booking the normalized booking
   * @return the request approval routing, or {@code null} when direct booking is allowed
   */
  @Nullable
  private BankAdmission.Admitted pendingRouting(
      @NotNull BankActor actor,
      @NotNull BankStaffRights staff,
      @NotNull BankLinkedBooking booking) {
    BankAccount account = booking.account();
    if (account == null) {
      return staff.staff() ? null : BankAdmission.Admitted.UNROUTED;
    }
    boolean direct = staff.canBook(booking.type(), account.getId());
    if (direct) {
      boolean overCeiling =
          booking.type() != BankBookingRequestType.DEPOSIT
              && policy.exceedsDirectBookingCeiling(actor, account.getId(), booking.amount());
      return overCeiling ? policy.routeCartelDirectBooking(actor, account, booking.amount()) : null;
    }
    return requireAdmitted(policy.admit(actor, account, null, booking.type(), booking.amount()));
  }

  /**
   * Returns the approved request routing or raises the ordinary Bank refusal.
   *
   * @param admission the current policy decision
   * @return the request routing
   */
  @NotNull
  private static BankAdmission.Admitted requireAdmitted(@NotNull BankAdmission admission) {
    return switch (admission) {
      case BankAdmission.Admitted admitted -> admitted;
      case BankAdmission.Rejection.NOT_FOUND ->
          throw new NotFoundException("Bank account not found");
      case BankAdmission.Rejection.NOT_REQUEST_CAPABLE ->
          throw new BadRequestException("This account does not accept booking requests");
    };
  }

  /**
   * Resolves the accounts, fixed custodians and actual counterparties under Bank rules.
   *
   * @param payment the typed submitted payment
   * @return the normalized Bank terms
   */
  @NotNull
  private BankLinkedBooking normalize(@NotNull BankLinkedPayment payment) {
    BankLinkedPayment.Terms terms = payment.terms();
    return switch (payment) {
      case BankLinkedPayment.Deposit deposit ->
          booking(
              BankBookingRequestType.DEPOSIT,
              lockActive(deposit.accountId()),
              null,
              deposit.holderId(),
              null,
              resolve(deposit.depositor()),
              terms);
      case BankLinkedPayment.Withdrawal withdrawal ->
          booking(
              BankBookingRequestType.WITHDRAWAL,
              lockActive(withdrawal.accountId()),
              null,
              withdrawal.holderId(),
              null,
              resolve(withdrawal.payee()),
              terms);
      case BankLinkedPayment.Transfer transfer -> normalizeTransfer(transfer);
      case BankLinkedPayment.HolderTransfer transfer ->
          booking(
              BankBookingRequestType.HOLDER_TRANSFER,
              null,
              null,
              transfer.sourceHolderId(),
              transfer.destinationHolderId(),
              null,
              terms);
      case BankLinkedPayment.Authorisation authorisation ->
          booking(
              BankBookingRequestType.AUTHORISATION,
              lockActive(authorisation.accountId()),
              null,
              authorisation.holderId(),
              null,
              null,
              terms);
    };
  }

  /**
   * Locks both account transfer accounts in the shared Bank order.
   *
   * @param transfer the typed transfer
   * @return the normalized transfer
   */
  @NotNull
  private BankLinkedBooking normalizeTransfer(@NotNull BankLinkedPayment.Transfer transfer) {
    UUID sourceId = transfer.sourceAccountId();
    UUID targetId = transfer.destinationAccountId();
    if (sourceId.equals(targetId)) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_SELF_TRANSFER, "Bank transfer accounts must differ");
    }
    BankAccount source;
    BankAccount target;
    if (BankPostingWriter.ACCOUNT_ID_ORDER.compare(sourceId, targetId) < 0) {
      source = lockActive(sourceId);
      target = lockActive(targetId);
    } else {
      target = lockActive(targetId);
      source = lockActive(sourceId);
    }
    return booking(
        BankBookingRequestType.TRANSFER,
        source,
        target,
        transfer.sourceHolderId(),
        transfer.destinationHolderId(),
        null,
        transfer.terms());
  }

  /**
   * Creates normalized terms and checks debit justification.
   *
   * @param type the Bank effect
   * @param account the principal account
   * @param target the destination account
   * @param holderId the custodian
   * @param targetHolderId the second custodian
   * @param counterparty the actual depositor or payee
   * @param terms the submitted amount and notes
   * @return the normalized booking
   */
  @NotNull
  private BankLinkedBooking booking(
      @NotNull BankBookingRequestType type,
      @Nullable BankAccount account,
      @Nullable BankAccount target,
      @NotNull UUID holderId,
      @Nullable UUID targetHolderId,
      @Nullable CounterpartySnapshot counterparty,
      @NotNull BankLinkedPayment.Terms terms) {
    if (account != null && type != BankBookingRequestType.DEPOSIT) {
      BankBookingGuards.requireDebitJustification(account, terms.justification());
    }
    return new BankLinkedBooking(
        type,
        account,
        target,
        writer.requireHolder(holderId),
        targetHolderId == null ? null : writer.requireHolder(targetHolderId),
        counterparty,
        terms.amount(),
        terms.note(),
        terms.justification());
  }

  /**
   * Locks the account and verifies that it is active.
   *
   * @param accountId the Bank account
   * @return the active account
   */
  @NotNull
  private BankAccount lockActive(@NotNull UUID accountId) {
    BankAccount account = writer.lockAccount(accountId);
    guards.requireActive(account);
    return account;
  }

  /**
   * Resolves the selected actual counterparty using the ordinary Bank directory and membership
   * rules.
   *
   * @param party the actual depositor or payee
   * @return the snapshot
   */
  @NotNull
  private CounterpartySnapshot resolve(@NotNull Counterparty party) {
    CounterpartySnapshot snapshot =
        switch (party) {
          case Counterparty.Member member ->
              ledger.resolveCounterparty(member.userId(), null, member.orgUnitId());
          case Counterparty.Accountless external ->
              ledger.resolveCounterparty(null, external.name(), external.orgUnitId());
        };
    if (snapshot == null
        || snapshot.handle().length() > MAX_COUNTERPARTY_LENGTH
        || HandleAnonymisation.isReserved(snapshot.handle())) {
      throw new BadRequestException("The linked payment requires a valid actual counterparty");
    }
    return snapshot;
  }
}

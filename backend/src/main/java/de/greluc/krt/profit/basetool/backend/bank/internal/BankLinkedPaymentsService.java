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
import de.greluc.krt.profit.basetool.backend.admin.api.TransferFeePolicy.RateSnapshot;
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
import de.greluc.krt.profit.basetool.backend.kernel.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.kernel.StringNormalization;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
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
  private final BankCaller caller;
  private final BankAccountRepository accounts;
  private final BankAccessRules policy;
  private final BankPostingWriter writer;
  private final BankHolderRepository holders;
  private final BankBookingGuards guards;
  private final BankLedgerService ledger;
  private final TransferFeePolicy feePolicy;
  private final EntityManager entityManager;
  private final BankLinkedRequestLiveSync liveSync;

  @Override
  @NotNull
  public BankLinkedOutcome submit(
      @NotNull BankLinkedAttempt attempt, @NotNull BankLinkedPayment payment) {
    BankLinkedDecisions.Prepared prepared = prepareSubmission(attempt, payment);
    return admit(prepared, payment, feePolicy.snapshot(), null);
  }

  @Override
  @NotNull
  public BankLinkedOutcome replace(
      @NotNull UUID previousRequestId,
      @NotNull BankLinkedAttempt previousAttempt,
      @NotNull BankLinkedAttempt nextAttempt,
      @NotNull BankLinkedPayment payment) {
    if (previousAttempt.id().equals(nextAttempt.id())
        || !previousAttempt.purpose().equals(nextAttempt.purpose())) {
      throw new BadRequestException("Replacement requires a fresh attempt for the same purpose");
    }
    BankLinkedDecisions.Prepared prepared = prepareSubmission(nextAttempt, payment);
    BankBookingRequest previous = requests.replacementSource(previousRequestId, previousAttempt);
    RateSnapshot rate =
        new RateSnapshot(Objects.requireNonNull(previous.getLinkedAttempt()).getFeeRate());
    return admit(prepared, payment, rate, previous);
  }

  /**
   * Validates the submission and prepares its owner before Bank locks any resources.
   *
   * @param attempt the new attempt
   * @param payment its submitted terms
   * @return the prepared owner
   */
  @NotNull
  private BankLinkedDecisions.Prepared prepareSubmission(
      @NotNull BankLinkedAttempt attempt, @NotNull BankLinkedPayment payment) {
    if (attempt.purpose().kind() != BankPurposeKind.AUEC_ORDER) {
      throw new BadRequestException("This purpose does not use the linked Bank contract");
    }
    requireValidTerms(payment.terms());
    return decisions.prepare(attempt, Action.SUBMIT);
  }

  /**
   * Runs current Bank admission with the authoritative new or historical fee basis.
   *
   * @param prepared the prepared owner and attempt
   * @param payment the immutable submitted terms
   * @param rate the authoritative rate for this payment
   * @param previous the verified failed Bank request for a replacement
   * @return the Bank outcome
   */
  @NotNull
  private BankLinkedOutcome admit(
      @NotNull BankLinkedDecisions.Prepared prepared,
      @NotNull BankLinkedPayment payment,
      @NotNull RateSnapshot rate,
      @Nullable BankBookingRequest previous) {
    if (!rate.equals(payment.terms().rate())) {
      throw new BadRequestException("The submitted fee rate does not match the payment fee basis");
    }
    BankActor actor = actors.of(prepared.actor());
    requirePreliminaryAdmission(actor, payment);
    BankLinkedBooking booking = normalize(payment, previous, this::lockActive);
    actor = actors.of(prepared.actor());
    BankStaffRights staff = policy.staffRights(actor);
    boolean samePlayer = samePlayer(payment);
    bookings.validateParties(booking, samePlayer);
    BigDecimal fee = bankFee(payment, booking, rate);
    BankLinkedAttemptRecord record =
        new BankLinkedAttemptRecord(prepared.attempt(), rate.rate(), fee, samePlayer);
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
      requireDirectTarget(staff, booking);
      outcome = bookings.execute(record, booking, prepared.actor(), null, true);
      liveSync.changed();
    } else {
      outcome =
          new BankLinkedOutcome.Pending(creation.createLinked(record, booking, actor, routing));
    }
    prepared.complete(outcome);
    return outcome;
  }

  @Override
  @NotNull
  public Quote quote(@NotNull BankLinkedPayment payment) {
    return quoteAdmission(payment, null);
  }

  @Override
  @NotNull
  public Quote quoteReplacement(
      @NotNull BankLinkedPayment payment,
      @NotNull UUID requestId,
      @NotNull BankLinkedAttempt attempt) {
    var previous = requests.previewReplacementSource(requestId, attempt);
    var rate = new RateSnapshot(Objects.requireNonNull(previous.getLinkedAttempt()).getFeeRate());
    if (!rate.equals(payment.terms().rate())) {
      throw new BadRequestException("The submitted fee rate does not match the payment fee basis");
    }
    return quoteAdmission(payment, previous);
  }

  private Quote quoteAdmission(BankLinkedPayment payment, @Nullable BankBookingRequest previous) {
    requireValidTerms(payment.terms());
    BankActor actor = caller.actor();
    requirePreliminaryAdmission(actor, payment);
    BankLinkedBooking booking = normalize(payment, previous, this::readActive);
    bookings.validateParties(booking, samePlayer(payment));
    BankStaffRights staff = policy.staffRights(actor);
    boolean pending = pendingRouting(actor, staff, booking) != null;
    BigDecimal debit = sourceDebit(booking, bankFee(payment, booking, payment.terms().rate()));
    if (!pending) {
      requireDirectTarget(staff, booking);
      requirePreviewCoverage(booking, debit);
    }
    return new Quote(pending, debit);
  }

  private static boolean samePlayer(BankLinkedPayment payment) {
    return switch (payment) {
      case BankLinkedPayment.Withdrawal withdrawal ->
          withdrawal.gamePayment() == GamePayment.SAME_PLAYER;
      case BankLinkedPayment.Transfer transfer -> transfer.gamePayment() == GamePayment.SAME_PLAYER;
      case BankLinkedPayment.Deposit _,
          BankLinkedPayment.AccountDeposit _,
          BankLinkedPayment.AccountTransfer _,
          BankLinkedPayment.AccountAuthorisation _,
          BankLinkedPayment.HolderTransfer _,
          BankLinkedPayment.Authorisation _ ->
          false;
    };
  }

  private BigDecimal bankFee(
      BankLinkedPayment payment, BankLinkedBooking booking, RateSnapshot rate) {
    return switch (booking.type()) {
      case AUTHORISATION, DEPOSIT -> BigDecimal.ZERO;
      case WITHDRAWAL, TRANSFER, HOLDER_TRANSFER ->
          samePlayer(payment) || payment instanceof BankLinkedPayment.AccountTransfer
              ? BigDecimal.ZERO
              : feePolicy.feeOn(booking.amount(), rate);
    };
  }

  private static BigDecimal sourceDebit(BankLinkedBooking booking, BigDecimal fee) {
    return switch (booking.type()) {
      case AUTHORISATION, DEPOSIT -> BigDecimal.ZERO;
      case WITHDRAWAL, TRANSFER -> booking.amount().add(fee);
      case HOLDER_TRANSFER -> fee;
    };
  }

  private static void requireDirectTarget(BankStaffRights staff, BankLinkedBooking booking) {
    if (booking.target() != null && !staff.canSee(booking.target().getId())) {
      throw new AccessDeniedException("Destination account is not visible to the caller");
    }
  }

  private void requirePreviewCoverage(BankLinkedBooking booking, BigDecimal debit) {
    if (booking.type() == BankBookingRequestType.DEPOSIT) {
      return;
    }
    BigDecimal required =
        booking.type() == BankBookingRequestType.AUTHORISATION ? booking.amount() : debit;
    if (required.signum() == 0) {
      return;
    }
    BankAccount account = booking.account();
    if (account == null) {
      account =
          accounts
              .findFirstByType(BankAccountType.CARTEL)
              .filter(cartel -> cartel.getStatus() == BankAccountStatus.ACTIVE)
              .orElseThrow(
                  () -> new BusinessConflictException("orders.money.bank.insufficientCoverage"));
    }
    try {
      guards.requireAccountCoverage(account, required);
    } catch (BankConflictException insufficient) {
      throw new BusinessConflictException("orders.money.bank.insufficientCoverage");
    }
  }

  private BankAccount readActive(UUID accountId) {
    return accounts
        .findById(accountId)
        .filter(account -> account.getStatus() == BankAccountStatus.ACTIVE)
        .orElseThrow(() -> new NotFoundException("Bank account not found"));
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
  public void requireCustodianUser(@NotNull UUID custodianId, @NotNull UUID expectedUserId) {
    if (!holders.existsByIdAndUserIdAndActiveTrue(custodianId, expectedUserId)) {
      throw new NotFoundException("The selected Bank custodian is unavailable for this member");
    }
  }

  @Override
  public void cancel(@NotNull UUID requestId, @NotNull BankLinkedAttempt attempt) {
    requests.cancelLinked(requestId, attempt, Action.CANCEL);
  }

  @Override
  public void cancelUnexecuted(@NotNull UUID requestId, @NotNull BankLinkedAttempt attempt) {
    requests.cancelLinked(requestId, attempt, Action.CANCEL_UNEXECUTED);
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
          case BankLinkedPayment.AccountDeposit deposit ->
              new AccountSelection(BankBookingRequestType.DEPOSIT, deposit.accountId(), null);
          case BankLinkedPayment.AccountTransfer transfer ->
              new AccountSelection(
                  BankBookingRequestType.TRANSFER,
                  transfer.sourceAccountId(),
                  transfer.destinationAccountId());
          case BankLinkedPayment.AccountAuthorisation authorisation ->
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
    if (booking.type() == BankBookingRequestType.DEPOSIT && booking.holder() == null) {
      return BankAdmission.Admitted.UNROUTED;
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
   * @param previous the verified failed Bank request for a replacement
   * @param account the active account lookup appropriate to the transaction
   * @return the normalized Bank terms
   */
  @NotNull
  private BankLinkedBooking normalize(
      @NotNull BankLinkedPayment payment,
      @Nullable BankBookingRequest previous,
      Function<UUID, BankAccount> account) {
    BankLinkedPayment.Terms terms = payment.terms();
    return switch (payment) {
      case BankLinkedPayment.Deposit deposit ->
          booking(
              BankBookingRequestType.DEPOSIT,
              account.apply(deposit.accountId()),
              null,
              deposit.holderId(),
              null,
              depositor(previous, deposit.holderId(), deposit.depositor()),
              terms);
      case BankLinkedPayment.Withdrawal withdrawal ->
          booking(
              BankBookingRequestType.WITHDRAWAL,
              account.apply(withdrawal.accountId()),
              null,
              withdrawal.holderId(),
              null,
              resolve(withdrawal.payee()),
              terms);
      case BankLinkedPayment.Transfer transfer ->
          normalizeTransfer(
              transfer.sourceAccountId(),
              transfer.destinationAccountId(),
              transfer.sourceHolderId(),
              transfer.destinationHolderId(),
              transfer.terms(),
              account);
      case BankLinkedPayment.AccountTransfer transfer ->
          normalizeTransfer(
              transfer.sourceAccountId(),
              transfer.destinationAccountId(),
              null,
              null,
              transfer.terms(),
              account);
      case BankLinkedPayment.AccountDeposit deposit ->
          booking(
              BankBookingRequestType.DEPOSIT,
              account.apply(deposit.accountId()),
              null,
              null,
              null,
              depositor(previous, null, deposit.depositor()),
              terms);
      case BankLinkedPayment.AccountAuthorisation authorisation ->
          booking(
              BankBookingRequestType.AUTHORISATION,
              account.apply(authorisation.accountId()),
              null,
              null,
              null,
              null,
              terms);
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
              account.apply(authorisation.accountId()),
              null,
              authorisation.holderId(),
              null,
              null,
              terms);
    };
  }

  /**
   * Loads the transfer accounts in the shared Bank order.
   *
   * @param sourceId the funding account
   * @param targetId the receiving account
   * @param sourceHolder the selected source custodian, when applicable
   * @param targetHolder the selected receiving custodian, when applicable
   * @param terms the submitted payment terms
   * @param account the active account lookup appropriate to the transaction
   * @return the normalized transfer
   */
  @NotNull
  private BankLinkedBooking normalizeTransfer(
      @NotNull UUID sourceId,
      @NotNull UUID targetId,
      @Nullable UUID sourceHolder,
      @Nullable UUID targetHolder,
      @NotNull BankLinkedPayment.Terms terms,
      Function<UUID, BankAccount> account) {
    if (sourceId.equals(targetId)) {
      throw new BankConflictException(
          BankConflictException.CODE_BANK_SELF_TRANSFER, "Bank transfer accounts must differ");
    }
    BankAccount source;
    BankAccount target;
    if (BankPostingWriter.ACCOUNT_ID_ORDER.compare(sourceId, targetId) < 0) {
      source = account.apply(sourceId);
      target = account.apply(targetId);
    } else {
      target = account.apply(targetId);
      source = account.apply(sourceId);
    }
    return booking(
        BankBookingRequestType.TRANSFER, source, target, sourceHolder, targetHolder, null, terms);
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
      @Nullable UUID holderId,
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
        holderId == null ? null : writer.requireHolder(holderId),
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

  /** Resolves a new depositor or retains the exact failed deposit's validated snapshot. */
  @NotNull
  private CounterpartySnapshot depositor(
      @Nullable BankBookingRequest previous, @Nullable UUID holderId, @NotNull Counterparty party) {
    if (previous == null) {
      return resolve(party);
    }
    BankHolder holder = previous.getRequestedHolder();
    CounterpartySnapshot snapshot = BankLinkedBookings.storedCounterparty(previous);
    if (previous.getType() != BankBookingRequestType.DEPOSIT
        || !Objects.equals(holderId, holder == null ? null : holder.getId())
        || snapshot == null
        || !matchesDepositor(party, snapshot)) {
      throw new BadRequestException(
          "Replacement requires the submitted Bank depositor and custodian");
    }
    return snapshot;
  }

  private static boolean matchesDepositor(Counterparty party, CounterpartySnapshot snapshot) {
    return switch (party) {
      case Counterparty.Member member ->
          member.userId().equals(snapshot.userId())
              && Objects.equals(member.orgUnitId(), snapshot.orgUnitId());
      case Counterparty.Accountless external ->
          snapshot.userId() == null
              && !HandleAnonymisation.isReserved(external.name())
              && Objects.equals(StringNormalization.trimToNull(external.name()), snapshot.handle())
              && Objects.equals(external.orgUnitId(), snapshot.orgUnitId());
      case Counterparty.Retained _ ->
          snapshot.userId() == null || HandleAnonymisation.isAnonymised(snapshot.handle());
    };
  }

  /** Resolves a newly selected counterparty under ordinary Bank identity rules. */
  @NotNull
  private CounterpartySnapshot resolve(@NotNull Counterparty party) {
    CounterpartySnapshot snapshot =
        switch (party) {
          case Counterparty.Member member ->
              ledger.resolveCounterparty(member.userId(), null, member.orgUnitId());
          case Counterparty.Accountless external ->
              ledger.resolveCounterparty(null, external.name(), external.orgUnitId());
          case Counterparty.Retained _ ->
              throw new BadRequestException(
                  "A retained depositor requires a failed linked deposit");
        };
    if (snapshot == null
        || snapshot.handle().length() > MAX_COUNTERPARTY_LENGTH
        || HandleAnonymisation.isReserved(snapshot.handle())) {
      throw new BadRequestException("The linked payment requires a valid actual counterparty");
    }
    return snapshot;
  }
}

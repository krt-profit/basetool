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

package de.greluc.krt.profit.basetool.backend;

import de.greluc.krt.profit.basetool.backend.bank.api.BankAccessPolicy;
import de.greluc.krt.profit.basetool.backend.bank.api.BankAccountRight;
import de.greluc.krt.profit.basetool.backend.bank.api.BankActors;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedAccountOptions;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedAttempt;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedEffectHandler;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedOutcome;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedPayment;
import de.greluc.krt.profit.basetool.backend.bank.api.BankLinkedPayments;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurpose;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurposeKind;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankEffects;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankEffects.Decision;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankEffects.Outcome;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderBankPort;
import de.greluc.krt.profit.basetool.backend.platform.api.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Translates the public Order and Bank coordination contracts in a shared transaction. */
@Component
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
class AuecBankComposition implements JobOrderBankPort, BankLinkedEffectHandler {
  private final BankLinkedPayments bank;
  private final BankLinkedAccountOptions accountOptions;
  private final JobOrderBankEffects order;
  private final BankAccessPolicy access;
  private final BankActors actors;
  private final AuthHelperService auth;
  private final RequestScopeResolver scope;

  @Override
  public void submit(@NotNull Payment payment) {
    bank.submit(attempt(payment.attemptId(), payment.purposeRef()), toBank(terms(payment)), pin());
  }

  @Override
  public void replace(@NotNull Payment payment, @NotNull PreviousAttempt predecessor) {
    bank.replace(
        predecessor.requestId(),
        attempt(predecessor.attemptId(), payment.purposeRef()),
        attempt(payment.attemptId(), payment.purposeRef()),
        toBank(terms(payment)),
        pin());
  }

  @Override
  public void cancelUnexecuted(
      @NotNull UUID requestId, @NotNull UUID attemptId, @NotNull UUID purposeRef) {
    bank.cancelUnexecuted(requestId, attempt(attemptId, purposeRef));
  }

  @Override
  public void requireCustodian(@NotNull UUID custodianId, @NotNull UUID userId) {
    bank.requireCustodianUser(custodianId, userId);
  }

  @Override
  public boolean canSeeAccount(@NotNull UUID accountId) {
    return canViewAccount(accountId, pin());
  }

  @Override
  public boolean canSelectCreditAccount(@NotNull UUID accountId) {
    return canViewAccount(accountId, null);
  }

  private boolean canViewAccount(UUID accountId, @Nullable UUID pin) {
    return access
        .accessTo(actors.of(auth.currentUserId().orElse(null)), accountId, pin)
        .has(BankAccountRight.VIEW);
  }

  @Override
  public @NotNull AccountOptions accountOptions(
      @NotNull UUID responsibleUnit,
      @NotNull UUID requestingUnit,
      boolean canDeposit,
      boolean canDesignate,
      boolean canDebit) {
    var options =
        accountOptions.candidates(
            new BankLinkedAccountOptions.Scope(
                responsibleUnit, requestingUnit, canDeposit, canDesignate, canDebit, pin()));
    return new AccountOptions(
        accounts(options.collection()),
        accounts(options.designation()),
        accounts(options.withdrawal()),
        accounts(options.transfer()),
        options.custodians().stream()
            .map(holder -> new CustodianRef(holder.id(), holder.userId(), holder.name()))
            .toList());
  }

  private static List<AccountRef> accounts(List<BankLinkedAccountOptions.Account> references) {
    return references.stream()
        .map(account -> new AccountRef(account.id(), account.name()))
        .toList();
  }

  @Override
  public @NotNull BankPurposeKind kind() {
    return BankPurposeKind.AUEC_ORDER;
  }

  @Override
  public void beforeEffect(
      @NotNull BankLinkedAttempt attempt, @NotNull Action action, @NotNull UUID actorId) {
    var orderAction =
        switch (action) {
          case SUBMIT -> JobOrderBankEffects.Action.SUBMIT;
          case CONFIRM -> JobOrderBankEffects.Action.CONFIRM;
          case REJECT -> JobOrderBankEffects.Action.REJECT;
          case CANCEL -> JobOrderBankEffects.Action.CANCEL;
          case CANCEL_UNEXECUTED -> JobOrderBankEffects.Action.CANCEL_UNEXECUTED;
          case REVERSE -> JobOrderBankEffects.Action.REVERSE;
        };
    order.prepare(attempt.id(), attempt.purpose().ref(), orderAction, actorId);
  }

  @Override
  public void onOutcome(
      @NotNull BankLinkedAttempt attempt,
      @NotNull BankLinkedOutcome outcome,
      @NotNull UUID actorId) {
    Decision decision =
        switch (outcome) {
          case BankLinkedOutcome.Pending pending ->
              new Decision(Outcome.WAITING, pending.requestId(), null);
          case BankLinkedOutcome.Posted posted ->
              new Decision(Outcome.POSTED, posted.transactionId(), null);
          case BankLinkedOutcome.Authorised authorised ->
              new Decision(Outcome.AUTHORISED, authorised.authorisationId(), null);
          case BankLinkedOutcome.Rejected rejected ->
              new Decision(Outcome.REJECTED, rejected.requestId(), null);
          case BankLinkedOutcome.Cancelled cancelled ->
              new Decision(Outcome.CANCELLED, cancelled.requestId(), null);
          case BankLinkedOutcome.Reversed reversed ->
              new Decision(
                  Outcome.REVERSED,
                  reversed.reversalTransactionId(),
                  reversed.originalTransactionId());
        };
    order.apply(attempt.id(), attempt.purpose().ref(), decision, actorId);
  }

  private static BankLinkedAttempt attempt(UUID id, UUID purposeRef) {
    return new BankLinkedAttempt(id, new BankPurpose(BankPurposeKind.AUEC_ORDER, purposeRef));
  }

  @Override
  public @NotNull Quote quote(@NotNull Terms terms) {
    var quote = bank.quote(toBank(terms), pin());
    return new Quote(quote.pending(), quote.sourceDebit());
  }

  @Override
  public @NotNull Quote quoteReplacement(
      @NotNull Terms terms, @NotNull UUID purposeRef, @NotNull PreviousAttempt predecessor) {
    var quote =
        bank.quoteReplacement(
            toBank(terms),
            predecessor.requestId(),
            attempt(predecessor.attemptId(), purposeRef),
            pin());
    return new Quote(quote.pending(), quote.sourceDebit());
  }

  private @Nullable UUID pin() {
    return scope.currentPin().orElse(null);
  }

  private static Terms terms(Payment payment) {
    return new Terms(
        payment.selection(),
        payment.amount(),
        payment.rate(),
        payment.actualPayment(),
        payment.counterparty());
  }

  private static BankLinkedPayment toBank(Terms payment) {
    var selected = payment.selection();
    var terms =
        new BankLinkedPayment.Terms(
            payment.amount(), payment.rate(), null, selected.justification());
    if (Boolean.TRUE.equals(selected.accountOnly())) {
      UUID account = Objects.requireNonNull(selected.accountId());
      return switch (selected.type()) {
        case DEPOSIT -> new BankLinkedPayment.AccountDeposit(account, depositor(payment), terms);
        case TRANSFER ->
            new BankLinkedPayment.AccountTransfer(
                account, Objects.requireNonNull(selected.targetAccountId()), terms);
        case AUTHORISATION -> new BankLinkedPayment.AccountAuthorisation(account, terms);
        case WITHDRAWAL, HOLDER_TRANSFER ->
            throw new IllegalArgumentException("The submitted Bank effect requires a custodian.");
      };
    }
    UUID custodian = Objects.requireNonNull(selected.custodianId());
    var game =
        payment.actualPayment()
            ? BankLinkedPayment.GamePayment.REPORTED
            : BankLinkedPayment.GamePayment.SAME_PLAYER;
    return switch (selected.type()) {
      case AUTHORISATION ->
          new BankLinkedPayment.Authorisation(
              Objects.requireNonNull(selected.accountId()), custodian, terms);
      case DEPOSIT ->
          new BankLinkedPayment.Deposit(
              Objects.requireNonNull(selected.accountId()), custodian, depositor(payment), terms);
      case WITHDRAWAL ->
          new BankLinkedPayment.Withdrawal(
              Objects.requireNonNull(selected.accountId()), custodian, party(payment), game, terms);
      case TRANSFER ->
          new BankLinkedPayment.Transfer(
              Objects.requireNonNull(selected.accountId()),
              custodian,
              Objects.requireNonNull(selected.targetAccountId()),
              Objects.requireNonNull(selected.targetCustodianId()),
              game,
              terms);
      case HOLDER_TRANSFER ->
          new BankLinkedPayment.HolderTransfer(
              custodian, Objects.requireNonNull(selected.targetCustodianId()), terms);
    };
  }

  private static BankLinkedPayment.Counterparty depositor(Terms payment) {
    return payment.counterparty() == null
        ? new BankLinkedPayment.Counterparty.Retained()
        : party(payment);
  }

  private static BankLinkedPayment.Counterparty party(Terms payment) {
    Counterparty party = Objects.requireNonNull(payment.counterparty());
    return party.userId() == null
        ? new BankLinkedPayment.Counterparty.Accountless(Objects.requireNonNull(party.name()), null)
        : new BankLinkedPayment.Counterparty.Member(Objects.requireNonNull(party.userId()), null);
  }
}

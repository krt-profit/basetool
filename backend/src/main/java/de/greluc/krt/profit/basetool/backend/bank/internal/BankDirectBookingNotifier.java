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

import de.greluc.krt.profit.basetool.backend.bank.api.events.BankNotices;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationCauseCollector;
import de.greluc.krt.profit.basetool.backend.notification.api.events.ActorRef;
import de.greluc.krt.profit.basetool.backend.notification.api.events.NotificationEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tells the members a direct booking by bank staff affects (REQ-BANK-059): the member paid out to,
 * the member who must take over aUEC moved between holders, and the responsible holders of a
 * debited or reversed account. Announces direct no-ledger authorisations to the responsible holders
 * of the funding account. A confirmed request has its own lifecycle notice.
 */
@Service
@RequiredArgsConstructor
public class BankDirectBookingNotifier {

  private final BankTransactionRepository transactionRepository;
  private final BankAccountRepository accountRepository;
  private final BankHolderRepository holderRepository;
  private final BankPostingRepository postingRepository;
  private final BankActorResolver actors;
  private final ApplicationEventPublisher eventPublisher;
  private final NotificationCauseCollector notificationCauses;
  private final BankLinkedAttemptRepository linkedAttempts;

  /**
   * Announces a booked withdrawal: a payout to the counterparty member, if any, and the debit to
   * the account's responsible holders.
   *
   * @param request the withdrawal
   * @param booked the booked transaction
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void withdrawalBooked(
      @NotNull BankWithdrawalRequest request, @NotNull BankTransactionDto booked) {
    withdrawalBooked(request, booked, true, null);
  }

  /**
   * Announces the debit and, for an actual payout, notifies the registered counterparty.
   *
   * @param request the withdrawal
   * @param booked the booked transaction
   * @param gamePayment whether the holder actually paid another player
   * @param causeId the linked booking cause, or {@code null} for an ordinary booking
   */
  @Transactional(propagation = Propagation.MANDATORY)
  void withdrawalBooked(
      @NotNull BankWithdrawalRequest request,
      @NotNull BankTransactionDto booked,
      boolean gamePayment,
      @Nullable UUID causeId) {
    BankTransaction transaction = transactionRepository.findById(booked.id()).orElse(null);
    String accountNo = accountNo(request.accountId());
    if (transaction == null || accountNo == null) {
      return;
    }
    ActorRef actor = actors.current();
    if (gamePayment && request.counterpartyUserId() != null) {
      announce(
          causeId,
          BankNotices.payoutBooked(
              booked.id(),
              request.counterpartyUserId(),
              request.amount(),
              transaction.getTransferFee(),
              accountNo,
              actor));
    }
    announce(
        causeId,
        BankNotices.accountDebited(
            booked.id(),
            request.accountId(),
            accountNo,
            "WITHDRAWAL",
            request.amount(),
            transaction.getTransferFee(),
            actor));
  }

  /**
   * Announces a booked account-to-account transfer to the source account's responsible holders.
   *
   * @param request the transfer
   * @param booked the booked transaction
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void transferBooked(
      @NotNull BankTransferRequest request, @NotNull BankTransactionDto booked) {
    transferBooked(request.sourceAccountId(), request.amount(), booked, null);
  }

  /**
   * Announces an account debit using the linked outcome cause when present.
   *
   * @param sourceId the debited account
   * @param amount the principal
   * @param booked the booked transaction
   * @param causeId the linked booking cause, or {@code null} for an ordinary booking
   */
  @Transactional(propagation = Propagation.MANDATORY)
  void transferBooked(
      @NotNull UUID sourceId,
      @NotNull BigDecimal amount,
      @NotNull BankTransactionDto booked,
      @Nullable UUID causeId) {
    BankTransaction transaction = transactionRepository.findById(booked.id()).orElse(null);
    String accountNo = accountNo(sourceId);
    if (transaction == null || accountNo == null) {
      return;
    }
    announce(
        causeId,
        BankNotices.accountDebited(
            booked.id(),
            sourceId,
            accountNo,
            "TRANSFER",
            amount,
            transaction.getTransferFee(),
            actors.current()));
  }

  /**
   * Announces a booked reversal to the responsible holders of every account it moved.
   *
   * @param originalId the original transaction
   * @param booked the reversal transaction
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void reversalBooked(@NotNull UUID originalId, @NotNull BankTransactionDto booked) {
    UUID causeId = linkedAttempts.findPostingLink(originalId).isPresent() ? booked.id() : null;
    ActorRef actor = actors.current();
    List<BankCounterLeg> legs = postingRepository.findLegsByTransactionIds(List.of(booked.id()));
    for (BankCounterLeg leg : legs) {
      announce(
          causeId,
          BankNotices.accountDebited(
              booked.id(),
              leg.accountId(),
              leg.accountNo(),
              "REVERSAL",
              leg.amount().abs(),
              BigDecimal.ZERO,
              actor));
    }
  }

  /**
   * Announces a booked holder-to-holder transfer to the receiving holder's member, who must take
   * the aUEC over; a holder without a member is skipped.
   *
   * @param request the holder transfer
   * @param booked the booked transaction
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void holderTransferBooked(
      @NotNull BankHolderTransferRequest request, @NotNull BankTransactionDto booked) {
    holderTransferBooked(request, booked, null);
  }

  /**
   * Announces a custody movement using the linked outcome cause when present.
   *
   * @param request the holder transfer
   * @param booked the booked transaction
   * @param causeId the linked booking cause, or {@code null} for an ordinary booking
   */
  @Transactional(propagation = Propagation.MANDATORY)
  void holderTransferBooked(
      @NotNull BankHolderTransferRequest request,
      @NotNull BankTransactionDto booked,
      @Nullable UUID causeId) {
    UUID recipient =
        holderRepository
            .findById(request.destinationHolderId())
            .map(BankHolder::getUser)
            .map(user -> user.getId())
            .orElse(null);
    if (recipient == null) {
      return;
    }
    announce(
        causeId,
        BankNotices.holderTransferBooked(
            booked.id(), recipient, request.amount(), actors.current()));
  }

  /**
   * Announces a direct authorisation to the funding account's responsible holders.
   *
   * @param authorisationId the recorded authorisation
   * @param account the funding account
   * @param amount the authorised amount
   */
  @Transactional(propagation = Propagation.MANDATORY)
  void fundsAuthorised(
      @NotNull UUID authorisationId, @NotNull BankAccount account, @NotNull BigDecimal amount) {
    notificationCauses.appendPreferred(
        authorisationId,
        BankNotices.fundsAuthorised(
            authorisationId, account.getId(), account.getAccountNo(), amount, actors.current()));
  }

  private void announce(@Nullable UUID causeId, NotificationEvent event) {
    if (causeId == null) {
      eventPublisher.publishEvent(event);
    } else {
      notificationCauses.appendPreferred(causeId, event);
    }
  }

  private @Nullable String accountNo(UUID accountId) {
    return accountRepository.findById(accountId).map(BankAccount::getAccountNo).orElse(null);
  }
}

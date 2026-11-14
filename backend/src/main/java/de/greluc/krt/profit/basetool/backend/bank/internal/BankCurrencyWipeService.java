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

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.bank.api.BankCurrencyWipe;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncPublisher;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopic;
import de.greluc.krt.profit.basetool.backend.livesync.api.LiveSyncTopicClass;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Kartellbank's part of a currency wipe (REQ-PATCH-041, REQ-PATCH-042, ADR-0384): it cancels
 * the pending requests submitted before the live time, then offsets exactly the balances as of the
 * live time in one wipe reset, so every later booking stays.
 *
 * <p>An account whose later debits exceed its later credits would be overdrawn by that offset; the
 * no-overdraft rule (REQ-BANK-006) then refuses the wipe as a whole. The request locks come before
 * the account locks, as in a request's confirmation.
 */
@Service
@RequiredArgsConstructor
class BankCurrencyWipeService implements BankCurrencyWipe {

  /** The sections of an account's detail room the reset changes. */
  private static final List<String> ACCOUNT_SECTIONS = List.of("account", "bookings", "chart");

  private final BankAccountRepository accounts;
  private final BankPostingRepository postings;
  private final BankHolderPostingRepository holderPostings;
  private final BankBookingGuards guards;
  private final BankWipeResetBooking resetBooking;
  private final BankCurrencyWipeRequests requests;
  private final BankAuditService auditService;
  private final LiveSyncPublisher liveSync;

  @Override
  @Transactional(readOnly = true)
  public @NotNull Effect preview(@NotNull Instant liveAt) {
    Map<UUID, BigDecimal> atLiveTime = accountBalancesBefore(liveAt);
    accounts
        .findAllById(atLiveTime.keySet())
        .forEach(account -> requireCoverage(account, atLiveTime));
    return new Effect(requests.countUnlinkedBefore(liveAt), sum(atLiveTime));
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Effect apply(@NotNull Instant liveAt, @NotNull UUID patchChangeId) {
    int cancelled = requests.cancelUnlinkedBefore(liveAt, patchChangeId);
    List<BankAccount> locked = accounts.findAllForUpdateOrderById();
    Map<UUID, BigDecimal> atLiveTime = accountBalancesBefore(liveAt);
    locked.forEach(account -> requireCoverage(account, atLiveTime));
    Map<UUID, BigDecimal> holderBalances =
        holderPostings.holderAmountsBefore(liveAt).stream()
            .collect(Collectors.toMap(BankHolderAmount::holderId, BankHolderAmount::amount));
    BankWipeResetBooking.Booked booked = resetBooking.book(locked, atLiveTime, holderBalances);
    auditService.record(
        BankAuditEventType.WIPE_RESET_EXECUTED,
        null,
        booked.transactionId(),
        null,
        AuditDetails.of("patchChange", patchChangeId)
            .with("liveAt", liveAt)
            .with("accounts", booked.accounts())
            .with("stashes", booked.stashes())
            .with("totalZeroed", plain(booked.total()))
            .with("cancelledRequests", cancelled));
    if (booked.transactionId() != null) {
      announce(locked, atLiveTime);
    }
    return new Effect(cancelled, booked.total());
  }

  /**
   * Reads every account's balance as of the live time.
   *
   * @param liveAt the live time
   * @return the balance by account id; an account without an earlier posting is absent
   */
  private @NotNull Map<UUID, BigDecimal> accountBalancesBefore(@NotNull Instant liveAt) {
    return postings.accountBalancesBefore(liveAt).stream()
        .collect(Collectors.toMap(BankAccountBalance::accountId, BankAccountBalance::balance));
  }

  /**
   * Refuses the wipe when the account's current balance cannot cover its offset.
   *
   * @param account the account
   * @param atLiveTime the balances as of the live time
   * @throws de.greluc.krt.profit.basetool.backend.bank.api.BankConflictException with {@code
   *     BANK_OVERDRAFT} when the offset would overdraw the account
   */
  private void requireCoverage(
      @NotNull BankAccount account, @NotNull Map<UUID, BigDecimal> atLiveTime) {
    BigDecimal offset = atLiveTime.get(account.getId());
    if (offset != null && offset.signum() > 0) {
      guards.requireAccountCoverage(account, offset);
    }
  }

  /**
   * Refreshes the open Bank pages after the commit: the staff grid and every account the reset
   * booked on.
   *
   * @param locked the locked accounts
   * @param atLiveTime the offset balances
   */
  private void announce(
      @NotNull List<BankAccount> locked, @NotNull Map<UUID, BigDecimal> atLiveTime) {
    liveSync.publishAfterCommit(
        LiveSyncTopic.global(LiveSyncTopicClass.BANK_STAFF), List.of("grid"));
    liveSync.publishAfterCommit(
        LiveSyncTopic.global(LiveSyncTopicClass.ORGUNIT_BANK), List.of("orgUnitBank"));
    locked.stream()
        .filter(account -> atLiveTime.getOrDefault(account.getId(), BigDecimal.ZERO).signum() != 0)
        .forEach(account ->
            liveSync.publishAfterCommit(
                LiveSyncTopic.of(LiveSyncTopicClass.BANK_ACCOUNT, account.getId()),
                ACCOUNT_SECTIONS));
  }

  /**
   * Sums the balances.
   *
   * @param balances the balances by id
   * @return their sum
   */
  private static @NotNull BigDecimal sum(@NotNull Map<UUID, BigDecimal> balances) {
    return balances.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
  }
}

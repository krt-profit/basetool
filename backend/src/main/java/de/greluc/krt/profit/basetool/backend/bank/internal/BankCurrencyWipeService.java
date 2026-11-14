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
 * the pending requests submitted before the live time, then offsets the balances as of the live
 * time in one wipe reset, so every later booking stays.
 *
 * <p>An account or holder whose later debits exceed its later credits would go below zero by that
 * offset; its offset then stops at zero ({@link BankWipeOffsets}). The request locks come before
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
  private final BankWipeResetBooking resetBooking;
  private final BankCurrencyWipeRequests requests;
  private final BankAuditService auditService;
  private final LiveSyncPublisher liveSync;

  @Override
  @Transactional(readOnly = true)
  public @NotNull Effect preview(@NotNull Instant liveAt) {
    BankWipeOffsets accountOffsets = accountOffsets(liveAt);
    return new Effect(
        requests.countUnlinkedBefore(liveAt), accountOffsets.total(), accountOffsets.clamped());
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull Effect apply(@NotNull Instant liveAt, @NotNull UUID patchChangeId) {
    int cancelled = requests.cancelUnlinkedBefore(liveAt, patchChangeId);
    List<BankAccount> locked = accounts.findAllForUpdateOrderById();
    BankWipeOffsets accountOffsets = accountOffsets(liveAt);
    BankWipeOffsets holderOffsets = holderOffsets(liveAt);
    BankWipeResetBooking.Booked booked =
        resetBooking.book(locked, accountOffsets.offsets(), holderOffsets.offsets());
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
            .with("clampedAccounts", accountOffsets.clamped())
            .with("clampedStashes", holderOffsets.clamped())
            .with("cancelledRequests", cancelled));
    if (booked.transactionId() != null) {
      announce(locked, accountOffsets.offsets());
    }
    return new Effect(cancelled, booked.total(), accountOffsets.clamped());
  }

  /**
   * Derives every account's offset from its balance as of the live time and its current balance.
   *
   * @param liveAt the live time
   * @return the offsets by account id
   */
  private @NotNull BankWipeOffsets accountOffsets(@NotNull Instant liveAt) {
    Map<UUID, BigDecimal> atLiveTime = balances(postings.accountBalancesBefore(liveAt));
    Map<UUID, BigDecimal> current =
        atLiveTime.isEmpty() ? Map.of() : balances(postings.accountBalances(atLiveTime.keySet()));
    return BankWipeOffsets.of(atLiveTime, current);
  }

  /**
   * Derives every holder's offset from its custody as of the live time and its current custody.
   *
   * @param liveAt the live time
   * @return the offsets by holder id
   */
  private @NotNull BankWipeOffsets holderOffsets(@NotNull Instant liveAt) {
    Map<UUID, BigDecimal> atLiveTime = amounts(holderPostings.holderAmountsBefore(liveAt));
    Map<UUID, BigDecimal> current =
        atLiveTime.isEmpty()
            ? Map.of()
            : amounts(holderPostings.holderAmountsOf(atLiveTime.keySet()));
    return BankWipeOffsets.of(atLiveTime, current);
  }

  /**
   * Keys account balances by account.
   *
   * @param balances the grouped balances
   * @return the balance by account id
   */
  private static @NotNull Map<UUID, BigDecimal> balances(
      @NotNull List<BankAccountBalance> balances) {
    return balances.stream()
        .collect(Collectors.toMap(BankAccountBalance::accountId, BankAccountBalance::balance));
  }

  /**
   * Keys holder amounts by holder.
   *
   * @param amounts the grouped amounts
   * @return the amount by holder id
   */
  private static @NotNull Map<UUID, BigDecimal> amounts(@NotNull List<BankHolderAmount> amounts) {
    return amounts.stream()
        .collect(Collectors.toMap(BankHolderAmount::holderId, BankHolderAmount::amount));
  }

  /**
   * Refreshes the open Bank pages after the commit: the staff grid and every account the reset
   * booked on.
   *
   * @param locked the locked accounts
   * @param offsets the offsets by account id
   */
  private void announce(@NotNull List<BankAccount> locked, @NotNull Map<UUID, BigDecimal> offsets) {
    liveSync.publishAfterCommit(
        LiveSyncTopic.global(LiveSyncTopicClass.BANK_STAFF), List.of("grid"));
    liveSync.publishAfterCommit(
        LiveSyncTopic.global(LiveSyncTopicClass.ORGUNIT_BANK), List.of("orgUnitBank"));
    locked.stream()
        .filter(account -> offsets.getOrDefault(account.getId(), BigDecimal.ZERO).signum() != 0)
        .forEach(account ->
            liveSync.publishAfterCommit(
                LiveSyncTopic.of(LiveSyncTopicClass.BANK_ACCOUNT, account.getId()),
                ACCOUNT_SECTIONS));
  }
}

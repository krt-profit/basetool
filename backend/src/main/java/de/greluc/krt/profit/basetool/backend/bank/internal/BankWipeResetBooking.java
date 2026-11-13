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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Books one {@code WIPE_RESET} transaction that offsets given balances on both ledgers
 * (REQ-BANK-013, ADR-0039): a negative account leg per non-zero account balance and a negative
 * holder leg per non-zero holder balance. The standalone reset offsets the current balances, a
 * patch change's currency wipe the balances as of its live time (ADR-0384).
 */
@Component
@RequiredArgsConstructor
class BankWipeResetBooking {

  /** The note every wipe reset transaction carries. */
  private static final String NOTE = "SC wipe reset";

  private final BankPostingWriter writer;

  /**
   * Books the reset; books nothing when every given balance is zero.
   *
   * @param accounts the accounts the caller locked, in lock order
   * @param accountBalances the balance to offset per account id; an account without one is skipped
   * @param holderBalances the balance to offset per holder id
   * @return what was booked
   */
  @Transactional(propagation = Propagation.MANDATORY)
  @NotNull
  Booked book(
      @NotNull List<BankAccount> accounts,
      @NotNull Map<UUID, BigDecimal> accountBalances,
      @NotNull Map<UUID, BigDecimal> holderBalances) {
    List<BankAccount> reset =
        accounts.stream().filter(account -> signum(accountBalances, account.getId()) != 0).toList();
    List<UUID> stashes =
        holderBalances.keySet().stream()
            .filter(holder -> signum(holderBalances, holder) != 0)
            .sorted()
            .toList();
    if (reset.isEmpty() && stashes.isEmpty()) {
      return new Booked(null, 0, 0, BigDecimal.ZERO);
    }
    Instant now = Instant.now();
    BankTransaction tx =
        writer.persistTransaction(
            BankTransactionType.WIPE_RESET, NOTE, null, null, null, BigDecimal.ZERO, now, null);
    BigDecimal total = BigDecimal.ZERO;
    for (BankAccount account : reset) {
      BigDecimal amount = Objects.requireNonNull(accountBalances.get(account.getId()));
      writer.persistAccountPosting(tx, account, amount.negate(), now);
      total = total.add(amount);
    }
    Map<UUID, BankHolder> holders = writer.loadHolders(stashes);
    for (UUID holderId : stashes) {
      writer.persistHolderPosting(
          tx,
          writer.requireHolder(holders, holderId),
          Objects.requireNonNull(holderBalances.get(holderId)).negate(),
          now);
    }
    return new Booked(tx.getId(), reset.size(), stashes.size(), total);
  }

  /**
   * Reads the sign of a balance a map may not hold.
   *
   * @param balances the balances by id
   * @param id the id
   * @return the signum, zero when the map holds no balance for the id
   */
  private static int signum(@NotNull Map<UUID, BigDecimal> balances, @NotNull UUID id) {
    BigDecimal amount = balances.get(id);
    return amount == null ? 0 : amount.signum();
  }

  /**
   * What a reset booked.
   *
   * @param transactionId the {@code WIPE_RESET} transaction, or {@code null} when nothing was
   *     booked
   * @param accounts the number of account legs
   * @param stashes the number of holder legs
   * @param total the sum of the offset account balances, in whole aUEC
   */
  record Booked(
      @Nullable UUID transactionId, int accounts, int stashes, @NotNull BigDecimal total) {}
}

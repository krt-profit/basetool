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

package de.greluc.krt.profit.basetool.backend.bank.api;

import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/**
 * The member-facing reads of the org-unit bank side for an explicit {@link BankActor}, decided by
 * {@link BankAccessPolicy} without a pin (REQ-BANK-038, REQ-BANK-049, REQ-BANK-064, REQ-BXCH-002).
 * An account the actor may not view, a closed and an unknown account are all absent alike. Must run
 * inside a transaction.
 */
public interface BankAccountReads {

  /**
   * One page of the active accounts the actor views, the KRT account first and the rest by account
   * number.
   *
   * @param actor the actor
   * @param afterAccountId the last account of the previous page, or {@code null} for the first page
   * @param limit the page size, at least 1
   * @return the page; empty when {@code afterAccountId} is no longer among the viewable accounts
   */
  @NotNull
  Optional<AccountPage> viewableAccounts(
      @NotNull BankActor actor, @Nullable UUID afterAccountId, int limit);

  /**
   * One page of the redacted booking history of a viewable account, newest first (REQ-BANK-038).
   *
   * @param actor the actor
   * @param accountId the account
   * @param from inclusive lower bound on the booking instant, or {@code null}
   * @param to inclusive upper bound on the booking instant, or {@code null}
   * @param after the last booking of the previous page, or {@code null} for the first page
   * @param limit the page size, at least 1
   * @return the page; empty when the account is unknown, closed or not viewable
   */
  @NotNull
  Optional<BookingPage> bookings(
      @NotNull BankActor actor,
      @NotNull UUID accountId,
      @Nullable Instant from,
      @Nullable Instant to,
      @Nullable BookingKey after,
      int limit);

  /**
   * The end-of-day balances of a viewable account, one per UTC day of the period (REQ-BANK-049).
   *
   * @param actor the actor
   * @param accountId the account
   * @param from inclusive period start
   * @param to inclusive period end
   * @return the series; empty when the account is unknown, closed or not viewable
   */
  @NotNull
  Optional<BalanceSeries> balanceSeries(
      @NotNull BankActor actor,
      @NotNull UUID accountId,
      @NotNull Instant from,
      @NotNull Instant to);

  /**
   * A viewable account with its figures and the two facts about the actor.
   *
   * @param accountId the account
   * @param accountNo the {@code KB-<n>} account number
   * @param name the display name
   * @param type the account type's name, such as {@code ORG_UNIT} or {@code CARTEL}
   * @param owner the owning org unit, or {@code null} for an account without one
   * @param balance the current balance in whole aUEC
   * @param balanceTarget the balance goal, or {@code null}
   * @param delta30d the net change over the last 30 days
   * @param sparkline the 30 end-of-day balances, oldest first, ending with the current balance
   * @param requestable whether the actor may raise a withdrawal or transfer here
   * @param ownLimit the actor's applicable limit above which a request needs an approval, or {@code
   *     null}
   */
  record Account(
      @NotNull UUID accountId,
      @NotNull String accountNo,
      @NotNull String name,
      @NotNull String type,
      @Nullable Owner owner,
      @NotNull BigDecimal balance,
      @Nullable BigDecimal balanceTarget,
      @NotNull BigDecimal delta30d,
      @NotNull @Unmodifiable List<BigDecimal> sparkline,
      boolean requestable,
      @Nullable BigDecimal ownLimit) {}

  /**
   * The org unit that owns an account.
   *
   * @param id the org unit
   * @param shorthand its short label
   * @param name its display name
   * @param kind its kind
   */
  record Owner(
      @NotNull UUID id,
      @NotNull String shorthand,
      @NotNull String name,
      @NotNull OrgUnitKind kind) {}

  /**
   * One page of viewable accounts.
   *
   * @param items the accounts in the fixed order
   * @param hasMore whether a further page follows the last item
   */
  record AccountPage(@NotNull @Unmodifiable List<Account> items, boolean hasMore) {}

  /**
   * One redacted booking row: no holders and no staff note (REQ-BANK-038).
   *
   * @param postingId the account leg
   * @param transactionId the owning transaction
   * @param type the transaction type's name, such as {@code DEPOSIT}
   * @param amount the signed amount of this account's leg
   * @param createdAt the booking instant
   * @param note the free-text note, or {@code null}
   * @param justification the justification of a debit, or {@code null}
   * @param reversedTransactionId the corrected transaction of a reversal, or {@code null}
   * @param counterAccountNo the other account's number of a transfer leg, or {@code null}
   * @param counterAccountName the other account's name of a transfer leg, or {@code null}
   * @param intraAccount whether both legs are on this account
   * @param transferFee the fee borne by the debited source; zero without one
   * @param counterpartyUserId the registered counterparty, or {@code null}
   * @param counterpartyHandle the counterparty's handle snapshot, or {@code null}
   * @param counterpartyOrgUnitName the counterparty's org-unit name snapshot, or {@code null}
   */
  record Booking(
      @NotNull UUID postingId,
      @NotNull UUID transactionId,
      @NotNull String type,
      @NotNull BigDecimal amount,
      @NotNull Instant createdAt,
      @Nullable String note,
      @Nullable String justification,
      @Nullable UUID reversedTransactionId,
      @Nullable String counterAccountNo,
      @Nullable String counterAccountName,
      boolean intraAccount,
      @NotNull BigDecimal transferFee,
      @Nullable UUID counterpartyUserId,
      @Nullable String counterpartyHandle,
      @Nullable String counterpartyOrgUnitName) {

    /**
     * The position of this booking in the newest-first order.
     *
     * @return its key
     */
    @NotNull
    public BookingKey key() {
      return new BookingKey(createdAt, postingId);
    }
  }

  /**
   * A position in the newest-first booking order.
   *
   * @param createdAt the booking instant
   * @param postingId the account leg, the tiebreaker within one instant
   */
  record BookingKey(@NotNull Instant createdAt, @NotNull UUID postingId) {}

  /**
   * One page of bookings.
   *
   * @param items the bookings, newest first
   * @param hasMore whether a further page follows the last item
   */
  record BookingPage(@NotNull @Unmodifiable List<Booking> items, boolean hasMore) {}

  /**
   * The daily balance series of an account.
   *
   * @param points one end-of-day balance per day, oldest first
   * @param balanceTarget the balance goal, or {@code null}
   */
  record BalanceSeries(
      @NotNull @Unmodifiable List<BalancePoint> points, @Nullable BigDecimal balanceTarget) {}

  /**
   * One end-of-day balance.
   *
   * @param date the UTC day
   * @param balance the balance at its end
   */
  record BalancePoint(@NotNull LocalDate date, @NotNull BigDecimal balance) {}
}

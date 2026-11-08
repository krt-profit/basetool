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

package de.greluc.krt.profit.basetool.backend.bank.web;

import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Account;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Actor;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Holder;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Pin;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Request;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The ids of one seeded bank world, as {@link BankAccessSeeder} created it.
 *
 * @param users the user id of every actor
 * @param usernames the callsign of every actor, sent as {@code preferred_username}
 * @param accounts the id of every seeded account
 * @param holders the id of every seeded holder
 * @param requests the id of every seeded pending request
 * @param pins the org unit each pin names; {@link Pin#NONE} names none
 * @param outsider a member of no org unit, the grantee of the settings operations
 */
record BankAccessWorld(
    @NotNull Map<Actor, UUID> users,
    @NotNull Map<Actor, String> usernames,
    @NotNull Map<Account, UUID> accounts,
    @NotNull Map<Holder, UUID> holders,
    @NotNull Map<Request, UUID> requests,
    @NotNull Map<Pin, UUID> pins,
    @NotNull UUID outsider) {

  /**
   * The user id of an actor.
   *
   * @param actor the actor
   * @return the actor's user id
   */
  @NotNull
  UUID user(@NotNull Actor actor) {
    return Objects.requireNonNull(users.get(actor), actor::name);
  }

  /**
   * The callsign of an actor.
   *
   * @param actor the actor
   * @return the actor's username
   */
  @NotNull
  String username(@NotNull Actor actor) {
    return Objects.requireNonNull(usernames.get(actor), actor::name);
  }

  /**
   * The id of a seeded account.
   *
   * @param account the account
   * @return its id
   */
  @NotNull
  UUID account(@NotNull Account account) {
    return Objects.requireNonNull(accounts.get(account), account::name);
  }

  /**
   * The id of a seeded holder.
   *
   * @param holder the holder
   * @return its id
   */
  @NotNull
  UUID holder(@NotNull Holder holder) {
    return Objects.requireNonNull(holders.get(holder), holder::name);
  }

  /**
   * The id of a seeded request.
   *
   * @param request the request
   * @return its id
   */
  @NotNull
  UUID request(@NotNull Request request) {
    return Objects.requireNonNull(requests.get(request), request::name);
  }

  /**
   * The org unit a pin names.
   *
   * @param pin the pin
   * @return the pinned org unit, or {@code null} for {@link Pin#NONE}
   */
  @Nullable
  UUID pinned(@NotNull Pin pin) {
    return pins.get(pin);
  }

  /**
   * The account a transfer from the given source is sent to, one the granted employees see: the KRT
   * account from the own Staffel's account, the own Staffel's account from every other.
   *
   * @param source the debited account
   * @return the destination account's id
   */
  @NotNull
  UUID transferTargetFor(@NotNull Account source) {
    return account(source == Account.OWN_ORG_UNIT ? Account.CARTEL : Account.OWN_ORG_UNIT);
  }

  /**
   * A destination no granted employee sees: the plain Sonderkonto, or the shared one when the plain
   * one is the source.
   *
   * @param source the debited account
   * @return the destination account's id
   */
  @NotNull
  UUID hiddenTransferTargetFor(@NotNull Account source) {
    return account(
        source == Account.SPECIAL_PLAIN ? Account.SPECIAL_SHARED : Account.SPECIAL_PLAIN);
  }

  /**
   * Names a seeded account by its id, for the listing outcomes.
   *
   * @param accountId an account id from a response
   * @return the seeded account, or {@code null} when the id belongs to no seeded account
   */
  @Nullable
  Account accountOf(@NotNull UUID accountId) {
    return accounts.entrySet().stream()
        .filter(entry -> entry.getValue().equals(accountId))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(null);
  }

  /**
   * Names a seeded request by its id, for the listing outcomes.
   *
   * @param requestId a request id from a response
   * @return the seeded request, or {@code null} when the id belongs to no seeded request
   */
  @Nullable
  Request requestOf(@NotNull UUID requestId) {
    return requests.entrySet().stream()
        .filter(entry -> entry.getValue().equals(requestId))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(null);
  }
}

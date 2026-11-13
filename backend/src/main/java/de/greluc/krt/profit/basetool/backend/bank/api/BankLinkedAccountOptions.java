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

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Minimal Bank references offered by an authorized linked Order action. */
public interface BankLinkedAccountOptions {

  /**
   * Finds bounded active-account and custodian references under the current actor's access.
   *
   * @param scope the order's units and granted actions
   * @return account and custodian candidates
   */
  @NotNull
  Options candidates(@NotNull Scope scope);

  /**
   * The already authorized Order actions and their owning units.
   *
   * @param responsibleUnit the unit collecting funds
   * @param requestingUnit the unit receiving the completed order
   * @param deposit whether the caller may deposit
   * @param designate whether the caller may designate a destination
   * @param debit whether the caller may provide from Bank funds
   * @param pin the trusted active org unit, or {@code null} without a selection
   */
  record Scope(
      UUID responsibleUnit,
      UUID requestingUnit,
      boolean deposit,
      boolean designate,
      boolean debit,
      @Nullable UUID pin) {}

  /**
   * Bounded references for the current financial action.
   *
   * @param collection accounts that can receive a deposit
   * @param designation visible accounts that can be designated
   * @param withdrawal accounts with a permitted withdrawal route
   * @param transfer accounts with a permitted transfer route
   * @param custodians selectable registered custody actors
   */
  record Options(
      List<Account> collection,
      List<Account> designation,
      List<Account> withdrawal,
      List<Account> transfer,
      List<Custodian> custodians) {}

  /**
   * An active account reference for an authorized choice.
   *
   * @param id the account
   * @param name its display label
   */
  record Account(UUID id, String name) {}

  /**
   * A registered Bank custodian visible to the caller.
   *
   * @param id the custody identity
   * @param userId its linked member
   * @param name its display label
   */
  record Custodian(UUID id, UUID userId, String name) {}
}

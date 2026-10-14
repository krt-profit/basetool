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
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Lets a module that raises purpose-bound requests pick and check its funding account without
 * reading the bank's internals (REQ-BANK-061).
 */
public interface FundingAccounts {

  /**
   * Finds an account that can fund purpose-bound requests: active and of type {@code CARTEL} or
   * {@code SPECIAL}.
   *
   * @param accountId the account
   * @return the account, or empty when it does not exist or cannot fund requests
   */
  @NotNull
  Optional<FundingAccountRef> findActive(@NotNull UUID accountId);

  /**
   * Lists the accounts an admin may choose as funding account.
   *
   * @return the active {@code CARTEL} and {@code SPECIAL} accounts by account number
   */
  @NotNull
  List<FundingAccountRef> listCandidates();
}

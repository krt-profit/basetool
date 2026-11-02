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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jetbrains.annotations.NotNull;

/**
 * Balances and 30-day posting slices of a set of accounts, loaded in two batch queries
 * (REQ-DATA-003, REQ-BANK-016).
 *
 * @param balances the balance per account id; accounts without postings are absent
 * @param slices the in-window posting slices per account id; accounts without slices are absent
 */
record BankAccountFigures(
    @NotNull Map<UUID, BigDecimal> balances, @NotNull Map<UUID, List<BankPostingSlice>> slices) {

  /**
   * Loads the figures of the given accounts with one balance and one windowed slice query.
   *
   * @param postingRepository the posting repository
   * @param accounts the accounts; must not be empty
   * @return the loaded figures
   */
  @NotNull
  static BankAccountFigures load(
      @NotNull BankPostingRepository postingRepository, @NotNull List<BankAccount> accounts) {
    List<UUID> ids = accounts.stream().map(BankAccount::getId).toList();
    Map<UUID, BigDecimal> balances = new HashMap<>();
    for (BankAccountBalance row : postingRepository.accountBalances(ids)) {
      balances.put(row.accountId(), row.balance());
    }
    Map<UUID, List<BankPostingSlice>> slices =
        postingRepository.postingSlicesSince(ids, BankTrendCalculator.windowCutoff()).stream()
            .collect(Collectors.groupingBy(BankPostingSlice::accountId));
    return new BankAccountFigures(balances, slices);
  }

  /**
   * The current balance of an account.
   *
   * @param accountId the account id
   * @return the balance; zero without postings
   */
  @NotNull
  BigDecimal balanceOf(@NotNull UUID accountId) {
    return balances.getOrDefault(accountId, BigDecimal.ZERO);
  }

  /**
   * The in-window posting slices of an account.
   *
   * @param accountId the account id
   * @return the slices; empty without postings in the window
   */
  @NotNull
  List<BankPostingSlice> slicesOf(@NotNull UUID accountId) {
    return slices.getOrDefault(accountId, List.of());
  }
}

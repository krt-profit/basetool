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

import de.greluc.krt.profit.basetool.backend.bank.api.FundingAccountRef;
import de.greluc.krt.profit.basetool.backend.bank.api.FundingAccounts;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Answers which accounts can fund purpose-bound requests (REQ-BANK-061). */
@Service
@RequiredArgsConstructor
public class BankFundingAccounts implements FundingAccounts {

  private final BankAccountRepository accountRepository;

  @Override
  @Transactional(readOnly = true)
  public @NotNull Optional<FundingAccountRef> findActive(@NotNull UUID accountId) {
    return accountRepository
        .findById(accountId)
        .filter(BankFundingAccounts::canFund)
        .map(BankFundingAccounts::toRef);
  }

  @Override
  @Transactional(readOnly = true)
  public @NotNull List<FundingAccountRef> listCandidates() {
    List<FundingAccountRef> candidates = new ArrayList<>();
    for (BankAccountType type : List.of(BankAccountType.CARTEL, BankAccountType.SPECIAL)) {
      accountRepository.findByTypeAndStatusOrderById(type, BankAccountStatus.ACTIVE).stream()
          .map(BankFundingAccounts::toRef)
          .forEach(candidates::add);
    }
    candidates.sort((a, b) -> a.accountNo().compareTo(b.accountNo()));
    return candidates;
  }

  private static boolean canFund(@NotNull BankAccount account) {
    return account.getStatus() == BankAccountStatus.ACTIVE
        && (account.getType() == BankAccountType.CARTEL
            || account.getType() == BankAccountType.SPECIAL);
  }

  private static @NotNull FundingAccountRef toRef(@NotNull BankAccount account) {
    return new FundingAccountRef(
        account.getId(), account.getAccountNo(), account.getName(), account.getType().name());
  }
}

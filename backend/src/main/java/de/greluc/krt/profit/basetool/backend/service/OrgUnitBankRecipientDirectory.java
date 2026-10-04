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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.model.BankAccountGrant;
import de.greluc.krt.profit.basetool.backend.notification.api.AccountRecipientDirectory;
import de.greluc.krt.profit.basetool.backend.repository.BankAccountGrantRepository;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * The bank module's {@link AccountRecipientDirectory}: grant holders from {@code
 * bank_account_grant}, responsible holders through {@link OrgUnitBankResponsibilityService}.
 *
 * <p>On the org-unit side of the bank, because the responsible holders follow org-unit membership
 * (ADR-0020).
 */
@Service
@RequiredArgsConstructor
public class OrgUnitBankRecipientDirectory implements AccountRecipientDirectory {

  private final BankAccountGrantRepository bankAccountGrantRepository;
  private final OrgUnitBankResponsibilityService orgUnitBankResponsibilityService;

  /**
   * The bank employees holding a {@code bank_account_grant} on an account (REQ-BANK-026).
   *
   * @param accountId the bank account
   * @return the grantees' user subs; never {@code null}, possibly empty
   */
  @Override
  @NotNull
  public Set<UUID> grantHoldersOf(@NotNull UUID accountId) {
    Set<UUID> holders = new HashSet<>();
    for (BankAccountGrant grant : bankAccountGrantRepository.findByAccountId(accountId)) {
      holders.add(grant.getId().getUserId());
    }
    return holders;
  }

  /**
   * The responsible holders of an account (REQ-BANK-034).
   *
   * @param accountId the bank account
   * @return their user subs; never {@code null}, empty for a Sonderkonto or an unlinked account
   */
  @Override
  @NotNull
  public Set<UUID> responsibleHoldersOf(@NotNull UUID accountId) {
    return orgUnitBankResponsibilityService.resolveResponsibleHolderUserIds(accountId);
  }
}

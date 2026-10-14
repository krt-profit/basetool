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

import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurpose;
import de.greluc.krt.profit.basetool.backend.bank.api.BankPurposeKind;
import de.greluc.krt.profit.basetool.backend.bank.api.PurposeBoundRequests;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The bank's command API for purpose-bound booking requests (REQ-BANK-061): it routes a request
 * through the same approval ladder and queue as a member's own and stamps the purpose on it; the
 * decisions that follow are the bank's and reach the owner through the purpose observer
 * (REQ-BANK-063).
 */
@Service
@RequiredArgsConstructor
public class BankPurposeBoundRequestsService implements PurposeBoundRequests {

  private final OrgUnitBankAccessService accessService;
  private final BankBookingRequestService requestService;

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull UUID requestWithdrawal(
      @NotNull UUID accountId,
      @NotNull BigDecimal amount,
      @NotNull UUID recipientUserId,
      @NotNull BankPurpose purpose,
      @NotNull String justification) {
    return accessService
        .raisePurposeBoundRequest(
            accountId,
            BankBookingRequestType.WITHDRAWAL,
            amount,
            justification,
            recipientUserId,
            purpose)
        .id();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public @NotNull UUID requestDeposit(
      @NotNull UUID accountId, @NotNull BigDecimal amount, @NotNull BankPurpose purpose) {
    return accessService
        .raisePurposeBoundRequest(
            accountId, BankBookingRequestType.DEPOSIT, amount, null, null, purpose)
        .id();
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void cancel(@NotNull UUID requestId, @NotNull BankPurposeKind kind) {
    requestService.cancelForPurpose(requestId, kind);
  }
}

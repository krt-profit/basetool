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

import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Raises and withdraws booking requests on behalf of another module, each carrying an opaque {@link
 * BankPurpose} (REQ-BANK-061). The calling member becomes the request's requester, so the four-eyes
 * rule (REQ-BANK-062) keeps them from granting or confirming it. The request then travels the
 * bank's ladder and queue unchanged; the owner of the purpose hears of its outcome through {@link
 * BookingRequestPurposeObserver}. Every call joins the caller's transaction.
 */
public interface PurposeBoundRequests {

  /**
   * Raises a {@code WITHDRAWAL} request from the funding account to a member.
   *
   * @param accountId the active {@code CARTEL} or {@code SPECIAL} account the money leaves
   * @param amount the whole-aUEC amount
   * @param recipientUserId the member who receives the money (the request's Empfänger)
   * @param purpose what the request pays for
   * @param justification the generated Begründung, which a debit of these accounts requires
   * @return the id of the pending request
   */
  @NotNull
  UUID requestWithdrawal(
      @NotNull UUID accountId,
      @NotNull BigDecimal amount,
      @NotNull UUID recipientUserId,
      @NotNull BankPurpose purpose,
      @NotNull String justification);

  /**
   * Raises a {@code DEPOSIT} request into the funding account.
   *
   * @param accountId the active account the money returns to
   * @param amount the whole-aUEC amount
   * @param purpose what the request is for
   * @return the id of the pending request
   */
  @NotNull
  UUID requestDeposit(
      @NotNull UUID accountId, @NotNull BigDecimal amount, @NotNull BankPurpose purpose);

  /**
   * Cancels a pending request of the given kind; the observer of the kind hears of it like of any
   * other cancellation.
   *
   * @param requestId the request to cancel
   * @param kind the kind the request must carry
   * @throws BankConflictException with {@code BANK_REQUEST_NOT_PENDING} when it is already decided
   */
  void cancel(@NotNull UUID requestId, @NotNull BankPurposeKind kind);
}

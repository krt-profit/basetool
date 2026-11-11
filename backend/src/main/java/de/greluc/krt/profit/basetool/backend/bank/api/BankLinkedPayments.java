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

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** Submits and cancels linked payments under the current actor in the caller's transaction. */
public interface BankLinkedPayments {

  /**
   * Admits a new linked attempt under the current Bank policy.
   *
   * @param attempt the stable attempt identity and opaque purpose
   * @param payment the immutable submitted terms
   * @return the synchronous pending, posted or authorised outcome
   */
  @NotNull
  BankLinkedOutcome submit(@NotNull BankLinkedAttempt attempt, @NotNull BankLinkedPayment payment);

  /**
   * Cancels a pending request using the required purpose owner's authority.
   *
   * @param requestId the Bank request to cancel
   * @param attempt the exact linked attempt
   */
  void cancel(@NotNull UUID requestId, @NotNull BankLinkedAttempt attempt);
}

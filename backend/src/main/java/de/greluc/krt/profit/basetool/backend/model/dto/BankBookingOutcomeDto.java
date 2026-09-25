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

package de.greluc.krt.profit.basetool.backend.model.dto;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Result of a bank-staff direct withdrawal / transfer (REQ-BANK-047, ADR-0109); exactly one field
 * is set. {@code transaction} means the booking went onto the ledger ({@code 201}); {@code
 * pendingRequest} means the amount exceeded the KRT ceiling {@code T1} and a pending request was
 * filed for the amount-band approver instead ({@code 202}).
 *
 * @param transaction the booked ledger transaction, else {@code null}
 * @param pendingRequest the filed pending booking request, else {@code null}
 */
public record BankBookingOutcomeDto(
    @Nullable BankTransactionDto transaction, @Nullable BankBookingRequestDto pendingRequest) {

  /**
   * Wraps a completed direct booking (nothing needed approval).
   *
   * @param transaction the booked ledger transaction
   * @return an outcome carrying only the transaction
   */
  @NotNull
  public static BankBookingOutcomeDto booked(BankTransactionDto transaction) {
    return new BankBookingOutcomeDto(transaction, null);
  }

  /**
   * Wraps an over-ceiling attempt that was filed as a pending request instead of booked.
   *
   * @param pendingRequest the filed pending booking request
   * @return an outcome carrying only the pending request
   */
  @NotNull
  public static BankBookingOutcomeDto requestRaised(BankBookingRequestDto pendingRequest) {
    return new BankBookingOutcomeDto(null, pendingRequest);
  }
}

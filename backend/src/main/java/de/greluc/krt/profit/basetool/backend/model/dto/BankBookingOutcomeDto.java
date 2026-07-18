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

import org.jetbrains.annotations.Nullable;

/**
 * The result of a bank-staff direct withdrawal / transfer (REQ-BANK-047, ADR-0109). Exactly one of
 * the two fields is set:
 *
 * <ul>
 *   <li>{@code transaction} — the booking went straight onto the ledger (the normal case, and the
 *       only outcome for a non-KRT account or a within-ceiling KRT amount); the endpoint answers
 *       {@code 201 Created}.
 *   <li>{@code pendingRequest} — the amount exceeded the KRT bank-employee ceiling {@code T1}, so
 *       nothing was booked; instead a {@code PENDING} booking request was filed, routed to the
 *       amount-band approver (Bankleitung for {@code T1..T2}, Organisationsleitung above {@code
 *       T2}) who must approve it before a bank employee confirms it onto the ledger; the endpoint
 *       answers {@code 202 Accepted}.
 * </ul>
 *
 * <p>The frontend branches on which field is present: a set {@code pendingRequest} renders the
 * "request filed, needs approval — tell the Bankleitung" notice instead of the booked-success flow.
 *
 * @param transaction the booked ledger transaction when the booking went through, else {@code null}
 * @param pendingRequest the filed pending booking request when the amount needed approval, else
 *     {@code null}
 */
public record BankBookingOutcomeDto(
    @Nullable BankTransactionDto transaction, @Nullable BankBookingRequestDto pendingRequest) {

  /**
   * Wraps a completed direct booking (nothing needed approval).
   *
   * @param transaction the booked ledger transaction
   * @return an outcome carrying only the transaction
   */
  public static BankBookingOutcomeDto booked(BankTransactionDto transaction) {
    return new BankBookingOutcomeDto(transaction, null);
  }

  /**
   * Wraps an over-ceiling attempt that was filed as a pending request instead of booked.
   *
   * @param pendingRequest the filed pending booking request
   * @return an outcome carrying only the pending request
   */
  public static BankBookingOutcomeDto requestRaised(BankBookingRequestDto pendingRequest) {
    return new BankBookingOutcomeDto(null, pendingRequest);
  }
}

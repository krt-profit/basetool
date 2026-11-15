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

import de.greluc.krt.profit.basetool.backend.platform.api.ObserverSpi;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Tells the owner of a purpose how its purpose-bound booking request ended, inside the bank's own
 * transaction (REQ-BANK-063): the owner's state equals the request's state when the transaction
 * commits, and a failing observer rolls the bank's decision back. Implemented by the module that
 * owns the kind; every method joins the caller's transaction ({@code MANDATORY}). The bank calls
 * {@link #beforeDecision} before it locks the request and the outcome methods after.
 */
@ObserverSpi
public interface BookingRequestPurposeObserver {

  /**
   * Names the purpose kind this observer owns.
   *
   * @return the kind whose requests this observer hears of
   */
  @NotNull
  BankPurposeKind kind();

  /**
   * Takes the owner's lock on a purpose before the bank locks one of its requests, so a decision of
   * the bank and the owner's own writes both lock the owner first and the request second
   * (REQ-BANK-063). Called again for a purpose whose lock the transaction already holds.
   *
   * @param purposeRef the owner's reference stored on the request
   */
  void beforeDecision(@NotNull UUID purposeRef);

  /**
   * A purpose-bound request was confirmed and booked.
   *
   * @param requestId the request
   * @param purposeRef the owner's reference stored on the request
   * @param transactionId the ledger transaction the confirmation booked
   */
  void onConfirmed(@NotNull UUID requestId, @NotNull UUID purposeRef, @NotNull UUID transactionId);

  /**
   * A purpose-bound request was rejected; no money moved.
   *
   * @param requestId the request
   * @param purposeRef the owner's reference stored on the request
   */
  void onRejected(@NotNull UUID requestId, @NotNull UUID purposeRef);

  /**
   * A purpose-bound request was cancelled, by its requester or by the owner itself; no money moved.
   *
   * @param requestId the request
   * @param purposeRef the owner's reference stored on the request
   */
  void onCancelled(@NotNull UUID requestId, @NotNull UUID purposeRef);
}

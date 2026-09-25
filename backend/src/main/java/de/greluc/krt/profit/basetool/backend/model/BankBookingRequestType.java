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

package de.greluc.krt.profit.basetool.backend.model;

/**
 * The kind of value movement requested via a {@link BankBookingRequest} (REQ-BANK-040). On
 * confirmation the matching {@link BankTransactionType} is booked; reversals never originate from a
 * request.
 */
public enum BankBookingRequestType {

  /** Money entered the bank for the account; books a {@code DEPOSIT} on confirmation. */
  DEPOSIT,

  /** Money left the account; books a {@code WITHDRAWAL} on confirmation. */
  WITHDRAWAL,

  /**
   * Money moves from the requested (source) account to a chosen destination account (REQ-BANK-040);
   * books a {@code TRANSFER} on confirmation via {@code BankLedgerService.bookTransfer}, with the
   * destination and both holders recorded by the bank employee at confirmation.
   */
  TRANSFER
}

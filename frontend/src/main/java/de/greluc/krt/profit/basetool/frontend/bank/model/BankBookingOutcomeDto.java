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

package de.greluc.krt.profit.basetool.frontend.bank.model;

/**
 * Result of a bank-staff direct withdrawal or transfer; exactly one field is set (REQ-BANK-047).
 *
 * @param transaction the booked transaction, or {@code null} when a request was filed instead
 * @param pendingRequest the pending request filed for an over-ceiling amount, or {@code null}
 */
public record BankBookingOutcomeDto(
    BankTransactionDto transaction, BankBookingRequestDto pendingRequest) {}

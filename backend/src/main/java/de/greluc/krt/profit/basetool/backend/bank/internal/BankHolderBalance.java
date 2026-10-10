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

import java.math.BigDecimal;
import java.util.UUID;

/**
 * JPQL constructor projection for one holder's <strong>global</strong> custody balance
 * (REQ-BANK-003, ADR-0039): the grouped {@code SUM} over the holder ledger ({@code
 * bank_holder_posting}) across the whole bank. Decoupled from accounts and may be negative
 * (REQ-BANK-006).
 *
 * @param holderId the {@code bank_holder} id
 * @param handle the holder's live display name for the report — the linked user's current effective
 *     name (display name preferred, username fallback), falling back to the deletion-proof handle
 *     snapshot when the user is gone (REQ-BANK-003)
 * @param holderActive whether the holder currently accepts new incoming postings
 * @param amount the signed global amount the holder physically holds across the whole bank
 */
public record BankHolderBalance(
    UUID holderId, String handle, boolean holderActive, BigDecimal amount) {}

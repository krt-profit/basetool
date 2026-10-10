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

/**
 * Result of the admin wipe reset (REQ-BANK-013). The operation is idempotent: on an already
 * all-zero bank nothing is booked and {@code accountsReset} is {@code 0} — the UI renders the no-op
 * notice from exactly this payload.
 *
 * @param accountsReset number of accounts whose balance was brought to zero
 * @param holderStashesZeroed number of holders whose global balance was brought to zero (ADR-0039)
 * @param totalZeroed sum of all zeroed account balances (whole aUEC)
 */
public record BankWipeResetResultDto(
    int accountsReset, int holderStashesZeroed, BigDecimal totalZeroed) {}

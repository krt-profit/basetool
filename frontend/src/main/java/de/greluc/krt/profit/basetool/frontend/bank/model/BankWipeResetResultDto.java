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

import java.math.BigDecimal;

/**
 * Frontend mirror of the wipe-reset result (REQ-BANK-013). {@code accountsReset == 0} means the
 * idempotent no-op case and renders the corresponding notice.
 *
 * @param accountsReset number of accounts that received a {@code WIPE_RESET} transaction
 * @param holderStashesZeroed number of (account, holder) sub-balances brought to zero
 * @param totalZeroed sum of all zeroed balances
 */
public record BankWipeResetResultDto(
    int accountsReset, int holderStashesZeroed, BigDecimal totalZeroed) {}

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
 * JPQL constructor projection pairing a bank account with its compute-on-read balance — the {@code
 * SUM} of its postings, grouped per account in a single statement (REQ-BANK-020, ADR-0010).
 *
 * @param accountId the {@code bank_account} id the sum belongs to
 * @param balance the signed sum of all posting amounts on the account; never {@code null} for a
 *     grouped row (an account without postings simply produces no row)
 */
public record BankAccountBalance(UUID accountId, BigDecimal balance) {}

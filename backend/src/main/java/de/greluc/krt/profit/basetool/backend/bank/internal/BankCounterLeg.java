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
 * JPQL constructor projection of one <strong>account</strong> ledger leg with its account labels,
 * fetched batched by transaction ids. Used to resolve the counter account of {@code TRANSFER} rows
 * for the booking history (one IN-query per page instead of per-row lookups) and to mirror the
 * account legs when building a {@code REVERSAL} (ADR-0010/0039). The holder dimension is mirrored
 * separately via {@link BankHolderLeg}.
 *
 * @param transactionId the owning transaction's id (the batch key)
 * @param postingId the leg's id, used to exclude the row's own leg when picking the counter leg
 * @param accountId the account the leg posts to
 * @param accountNo the account's display number (e.g. {@code KB-0003})
 * @param accountName the account's display name
 * @param amount the leg's signed amount
 */
public record BankCounterLeg(
    UUID transactionId,
    UUID postingId,
    UUID accountId,
    String accountNo,
    String accountName,
    BigDecimal amount) {}

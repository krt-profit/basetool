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
 * JPQL constructor projection of one <strong>holder</strong> ledger leg with its holder label,
 * fetched batched by transaction ids (ADR-0039). Used to (a) annotate an account's booking history
 * and statement rows with the holder who handled the movement — matched to the account leg by
 * amount sign — and (b) mirror the holder legs when building a {@code REVERSAL}.
 *
 * @param transactionId the owning transaction's id (the batch key)
 * @param holderId the holder whose stash the leg changes
 * @param handle the holder's live display name — the linked user's current effective name (display
 *     name preferred, username fallback), falling back to the deletion-proof handle snapshot when
 *     the user is gone (REQ-BANK-003)
 * @param amount the leg's signed amount
 */
public record BankHolderLeg(UUID transactionId, UUID holderId, String handle, BigDecimal amount) {}

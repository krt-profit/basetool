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

import jakarta.validation.constraints.NotNull;

/**
 * Write payload for toggling a holder's active flag (REQ-BANK-003). Deactivation blocks new
 * incoming postings naming the holder; history and existing custody stay untouched (holders are
 * never hard-deleted).
 *
 * @param active the new flag value
 * @param version optimistic-locking version the client read; a mismatch surfaces as 409 {@code
 *     OPTIMISTIC_LOCK}
 */
public record UpdateBankHolderRequest(@NotNull Boolean active, @NotNull Long version) {}

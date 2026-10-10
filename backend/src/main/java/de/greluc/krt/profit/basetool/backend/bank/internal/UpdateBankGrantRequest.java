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
 * Write payload for changing a grant's capability flags (REQ-BANK-009). View access is the row's
 * existence and not a flag — revoking view access means deleting the grant, never patching it.
 *
 * @param canDeposit new deposit capability
 * @param canWithdraw new withdrawal capability
 * @param canTransfer new transfer/rebooking capability
 * @param version optimistic-locking version the client read; a mismatch surfaces as 409 {@code
 *     OPTIMISTIC_LOCK}
 */
public record UpdateBankGrantRequest(
    @NotNull Boolean canDeposit,
    @NotNull Boolean canWithdraw,
    @NotNull Boolean canTransfer,
    @NotNull Long version) {}

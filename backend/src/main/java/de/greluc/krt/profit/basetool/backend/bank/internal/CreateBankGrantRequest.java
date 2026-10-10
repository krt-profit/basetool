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
import java.util.UUID;

/**
 * Write payload for creating a per-account grant (REQ-BANK-009). The grantee must hold the Bank
 * Employee role (or above) at creation time — validated in {@code BankGrantService} with the stable
 * 409 code {@code BANK_GRANTEE_MISSING_ROLE}. Org-unit memberships are irrelevant in both
 * directions (REQ-BANK-008).
 *
 * @param userId the grantee
 * @param accountId the granted account
 * @param canDeposit initial deposit capability
 * @param canWithdraw initial withdrawal capability
 * @param canTransfer initial transfer/rebooking capability
 */
public record CreateBankGrantRequest(
    @NotNull UUID userId,
    @NotNull UUID accountId,
    boolean canDeposit,
    boolean canWithdraw,
    boolean canTransfer) {}

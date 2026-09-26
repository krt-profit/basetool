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

package de.greluc.krt.profit.basetool.backend.model.dto;

import java.util.UUID;

/**
 * Response payload for one per-account bank grant row (REQ-BANK-009).
 *
 * @param userId the grantee's user id (one half of the composite key)
 * @param userHandle the grantee's effective display name
 * @param accountId the granted account's id (the other half of the composite key)
 * @param accountNo the account's display number
 * @param accountName the account's display name
 * @param canDeposit whether the grantee may book deposits onto the account
 * @param canWithdraw whether the grantee may book withdrawals from the account
 * @param canTransfer whether the grantee may transfer out of / rebook within the account
 * @param granteeHasBankRole {@code false} marks the grant inert (grantee lacks the Bank Employee
 *     role)
 * @param version optimistic-locking version the client must echo on flag changes
 */
public record BankGrantDto(
    UUID userId,
    String userHandle,
    UUID accountId,
    String accountNo,
    String accountName,
    boolean canDeposit,
    boolean canWithdraw,
    boolean canTransfer,
    boolean granteeHasBankRole,
    Long version) {}

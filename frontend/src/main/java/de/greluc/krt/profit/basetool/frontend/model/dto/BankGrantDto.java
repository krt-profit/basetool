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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.util.UUID;

/**
 * Frontend mirror of one grant-matrix row (REQ-BANK-009).
 *
 * @param userId the grantee's user id
 * @param userHandle the grantee's effective display name
 * @param accountId the granted account's id
 * @param accountNo the account's display number
 * @param accountName the account's display name
 * @param canDeposit deposit capability flag
 * @param canWithdraw withdrawal capability flag
 * @param canTransfer transfer/rebooking capability flag
 * @param granteeHasBankRole whether the grantee holds the Bank Employee role; {@code false} renders
 *     the row inert
 * @param version optimistic-locking version to echo on flag changes
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

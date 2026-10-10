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
 * One individual-user approval limit configured on a bank account (REQ-BANK-041) — the {@code USER}
 * limit rows shown in the account's approval-limit panel, with the user's display handle resolved
 * for rendering.
 *
 * @param userId the addressed user's id (the value echoed back to clear the limit)
 * @param displayName the user's effective display name (handle) for the panel
 * @param limitAmount the whole-aUEC ceiling configured for this user
 */
public record BankApprovalLimitUserDto(UUID userId, String displayName, BigDecimal limitAmount) {}

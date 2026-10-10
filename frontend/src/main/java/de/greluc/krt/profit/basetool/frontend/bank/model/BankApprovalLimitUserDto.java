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

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Frontend mirror of one individual-user approval limit (REQ-BANK-041) shown in the account's
 * approval-limit panel, with the user's display handle resolved by the backend.
 *
 * @param userId the addressed user's id (echoed back to clear the limit)
 * @param displayName the user's effective display name (handle)
 * @param limitAmount the whole-aUEC ceiling configured for this user
 */
@DtoMirror
public record BankApprovalLimitUserDto(UUID userId, String displayName, BigDecimal limitAmount) {}

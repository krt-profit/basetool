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
 * Write payload for registering a holder (REQ-BANK-003). Every v1 holder is a registered basetool
 * user picked via the user lookup; the handle snapshot is derived server-side from the user's
 * effective name at registration time.
 *
 * @param userId the basetool user to register as custodian; one holder row per user
 */
public record RegisterBankHolderRequest(@NotNull UUID userId) {}

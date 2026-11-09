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

package de.greluc.krt.profit.basetool.backend.exchange.internal.dto;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The account a member's booking request is raised against (REQ-BXCH-003).
 *
 * @param id the account's id, opaque to the client
 * @param no the {@code KB-<n>} account number
 * @param name the display name
 * @param orgUnitShorthand the owning org unit's shorthand, or {@code null} for an account without
 *     one
 */
public record ExchangeBankRequestAccountDto(
    @NotNull UUID id,
    @NotNull String no,
    @NotNull String name,
    @Nullable String orgUnitShorthand) {}

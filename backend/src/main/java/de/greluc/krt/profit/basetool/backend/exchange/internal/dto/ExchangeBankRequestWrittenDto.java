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

/**
 * The terse answer to a raised or corrected request (REQ-BXCH-004): no account name, number or org
 * unit.
 *
 * @param id the request
 * @param status the lifecycle state, such as {@code PENDING}
 * @param version the version after the write
 * @param amount the whole-aUEC amount
 */
public record ExchangeBankRequestWrittenDto(
    @NotNull UUID id, @NotNull String status, long version, long amount) {}

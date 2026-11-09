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

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * The corrected values of one of the member's pending requests (REQ-BXCH-004, REQ-BANK-056), each
 * replacing the stored one; the bank checks them by the web's rules.
 *
 * @param amount the corrected whole-aUEC amount
 * @param note the corrected note, or {@code null} to clear it
 * @param justification the corrected justification, or {@code null} to clear it
 * @param targetAccountId the corrected destination of a transfer, else {@code null}
 * @param version the version the client read
 */
public record ExchangeBankRequestCorrectionDto(
    @NotNull BigDecimal amount,
    @Nullable String note,
    @Nullable String justification,
    @Nullable UUID targetAccountId,
    long version) {}

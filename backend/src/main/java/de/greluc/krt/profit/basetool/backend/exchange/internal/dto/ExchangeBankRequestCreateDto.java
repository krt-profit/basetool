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

import de.greluc.krt.profit.basetool.backend.bank.api.BankBookingRequestType;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * A booking request a connected application raises for the member (REQ-BXCH-004): type, account and
 * amount are required, everything else the bank checks by the web's rules.
 *
 * @param type deposit, withdrawal or transfer
 * @param sourceAccountId the (source) account
 * @param targetAccountId the destination of a transfer, else {@code null}
 * @param amount the whole-aUEC amount
 * @param note the member's note, or {@code null}
 * @param justification the member's justification, or {@code null}
 * @param splitEnabled whether a deposit distributes {@code splitPercent} across squadron accounts
 * @param splitPercent the whole percent to distribute, or {@code null} without a split
 */
public record ExchangeBankRequestCreateDto(
    @NotNull BankBookingRequestType type,
    @NotNull UUID sourceAccountId,
    @Nullable UUID targetAccountId,
    @NotNull BigDecimal amount,
    @Nullable String note,
    @Nullable String justification,
    boolean splitEnabled,
    @Nullable BigDecimal splitPercent) {}

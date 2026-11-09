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

import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * One booking of a viewable account in the shape of the published {@code bank-booking.schema.json}
 * (REQ-BXCH-002): the member detail page's redaction, without holders and staff note.
 *
 * @param postingId this account's leg, opaque to the client
 * @param transactionId the owning transaction
 * @param type the transaction type, such as {@code DEPOSIT} or {@code TRANSFER}
 * @param amount the signed amount of this account's leg in whole aUEC
 * @param createdAt the booking instant
 * @param note the free-text note, or {@code null}
 * @param justification the justification of a debit, or {@code null}
 * @param reversedTransactionId the corrected transaction of a reversal, or {@code null}
 * @param counterAccount the other account of a transfer leg, or {@code null}
 * @param intraAccount whether both legs are on this account
 * @param transferFee the fee borne by the debited source in whole aUEC, or {@code null} without one
 * @param counterpartyHandle the counterparty's handle, or {@code null} when none was recorded or
 *     the counterparty withholds their name (REQ-XCH-044)
 * @param counterpartyOrgUnitName the counterparty's org-unit name, or {@code null}
 */
public record ExchangeBankBookingDto(
    @NotNull UUID postingId,
    @NotNull UUID transactionId,
    @NotNull String type,
    long amount,
    @NotNull Instant createdAt,
    @Nullable String note,
    @Nullable String justification,
    @Nullable UUID reversedTransactionId,
    @Nullable ExchangeBankCounterAccountDto counterAccount,
    boolean intraAccount,
    @Nullable Long transferFee,
    @Nullable String counterpartyHandle,
    @Nullable String counterpartyOrgUnitName) {}

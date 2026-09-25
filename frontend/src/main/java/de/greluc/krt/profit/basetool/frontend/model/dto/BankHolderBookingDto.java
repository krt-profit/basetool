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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of one row of a holder's custody history (REQ-BANK-032): the holder's ledger leg
 * with the account it moved on and, for an Umbuchung, the counter holder.
 *
 * @param postingId the holder leg's id
 * @param transactionId the owning transaction's id (the reversal target)
 * @param type transaction type enum name ({@code DEPOSIT}, {@code WITHDRAWAL}, {@code TRANSFER},
 *     {@code HOLDER_TRANSFER}, {@code WIPE_RESET}, {@code REVERSAL})
 * @param amount the signed amount of this holder's leg (positive: received; negative: paid out)
 * @param note the transaction's free-text note, may be {@code null}
 * @param createdAt the booking instant (UTC)
 * @param reversedTransactionId for reversal rows the corrected transaction's id, else {@code null}
 * @param counterAccountNo the account number this leg moved on, {@code null} for an Umbuchung or
 *     wipe reset
 * @param counterAccountName the matching account's name
 * @param counterHolderHandle for a {@code HOLDER_TRANSFER} the other holder of the Umbuchung
 * @param transferFee the in-game transfer fee borne by the debited source (REQ-BANK-033); {@code 0}
 *     for non-fee rows; an outgoing leg is the gross debited
 */
public record BankHolderBookingDto(
    UUID postingId,
    UUID transactionId,
    @BackendEnumAsString String type,
    BigDecimal amount,
    @Nullable String note,
    Instant createdAt,
    @Nullable UUID reversedTransactionId,
    @Nullable String counterAccountNo,
    @Nullable String counterAccountName,
    @Nullable String counterHolderHandle,
    BigDecimal transferFee) {}

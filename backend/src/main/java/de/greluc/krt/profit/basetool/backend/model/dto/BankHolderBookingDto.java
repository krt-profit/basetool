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

package de.greluc.krt.profit.basetool.backend.model.dto;

import de.greluc.krt.profit.basetool.backend.model.BankTransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * One row of a holder's custody history (REQ-BANK-032): this holder's ledger leg with the account
 * it moved on and, for a holder-to-holder Umbuchung, the counter holder.
 *
 * @param postingId the holder leg's id (stable row identity)
 * @param transactionId the owning transaction's id (the reversal target)
 * @param type the transaction type driving the chip rendering
 * @param amount the signed amount of THIS holder's leg (positive: received; negative: paid out)
 * @param note the transaction's free-text note, may be {@code null}
 * @param createdAt the booking instant (UTC)
 * @param reversedTransactionId for {@code REVERSAL} rows the corrected transaction's id, else
 *     {@code null}
 * @param counterAccountNo the account number this leg moved on, {@code null} for a {@code
 *     HOLDER_TRANSFER} or {@code WIPE_RESET}
 * @param counterAccountName the matching account's name, like {@code counterAccountNo}
 * @param counterHolderHandle for a {@code HOLDER_TRANSFER} the other holder, else {@code null}
 * @param transferFee the transfer fee borne by the debited source (REQ-BANK-033), {@code 0} for
 *     non-fee rows; an outgoing leg is the gross debited amount
 */
public record BankHolderBookingDto(
    UUID postingId,
    UUID transactionId,
    BankTransactionType type,
    BigDecimal amount,
    @Nullable String note,
    Instant createdAt,
    @Nullable UUID reversedTransactionId,
    @Nullable String counterAccountNo,
    @Nullable String counterAccountName,
    @Nullable String counterHolderHandle,
    BigDecimal transferFee) {}

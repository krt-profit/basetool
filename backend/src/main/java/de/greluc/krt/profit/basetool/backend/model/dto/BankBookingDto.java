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
 * One row of an account's booking history: this account's ledger leg with its transaction context
 * and, for transfers, the resolved counter side.
 *
 * @param postingId the leg's id (stable row identity)
 * @param transactionId the owning transaction's id (the reversal target)
 * @param type the transaction type driving the chip rendering
 * @param amount the signed amount of THIS account's leg
 * @param holderHandle the holder whose stash this leg changed
 * @param note the transaction's free-text note, may be {@code null}
 * @param justification the Begr&uuml;ndung of a {@code WITHDRAWAL} / {@code TRANSFER}
 *     (REQ-BANK-045), else {@code null}
 * @param staffNote the booking employee's note (REQ-BANK-054); {@code null} when none or on a
 *     redacted member-facing row
 * @param createdAt the booking instant (UTC)
 * @param reversedTransactionId for {@code REVERSAL} rows the corrected transaction's id, else
 *     {@code null}
 * @param counterAccountNo for transfer legs the other account's number, else {@code null} (also for
 *     intra-account transfers)
 * @param counterAccountName for transfer legs the other account's name, like {@code
 *     counterAccountNo}
 * @param counterHolderHandle for transfer legs the holder on the other leg, else {@code null}
 * @param intraAccount {@code true} for holder rebookings where both legs are on this account
 *     (REQ-BANK-011)
 * @param transferFee the transfer fee borne by the debited source (REQ-BANK-033), {@code 0} for
 *     non-fee rows; an outgoing leg is the gross debited amount
 * @param counterpartyHandle for a {@code DEPOSIT}/{@code WITHDRAWAL} the recorded counterparty's
 *     handle (REQ-BANK-044), else {@code null}
 * @param counterpartyOrgUnitName the counterparty's org-unit name, or {@code null} when none was
 *     recorded
 */
public record BankBookingDto(
    UUID postingId,
    UUID transactionId,
    BankTransactionType type,
    BigDecimal amount,
    String holderHandle,
    @Nullable String note,
    @Nullable String justification,
    @Nullable String staffNote,
    Instant createdAt,
    @Nullable UUID reversedTransactionId,
    @Nullable String counterAccountNo,
    @Nullable String counterAccountName,
    @Nullable String counterHolderHandle,
    boolean intraAccount,
    BigDecimal transferFee,
    @Nullable String counterpartyHandle,
    @Nullable String counterpartyOrgUnitName) {}

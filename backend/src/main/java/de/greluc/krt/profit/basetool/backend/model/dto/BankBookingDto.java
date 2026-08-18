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
 * One row of an account's booking history (K1 mockup): this account's ledger leg with its
 * transaction context and — for transfers — the resolved counter side.
 *
 * @param postingId the leg's id (stable row identity)
 * @param transactionId the owning transaction's id (the reversal target)
 * @param type the transaction type driving the chip rendering
 * @param amount the signed amount of THIS account's leg
 * @param holderHandle the holder whose stash this leg changed
 * @param note the transaction's free-text note, may be {@code null}
 * @param justification the transaction's free-text justification (Begr&uuml;ndung) — only a {@code
 *     WITHDRAWAL} / {@code TRANSFER} carries one (REQ-BANK-045), {@code null} otherwise
 * @param staffNote the booking bank employee's own free-text note ("Notiz Bankmitarbeiter",
 *     REQ-BANK-054); {@code null} when none was recorded, and always {@code null} on an org-unit
 *     member-facing row, where it is redacted like the Halter columns (REQ-BANK-038)
 * @param createdAt the booking instant (UTC)
 * @param reversedTransactionId for {@code REVERSAL} rows the corrected transaction's id, else
 *     {@code null}
 * @param counterAccountNo for transfer legs the other account's number, {@code null} otherwise or
 *     when the transfer is intra-account
 * @param counterAccountName for transfer legs the other account's name, like {@code
 *     counterAccountNo}
 * @param counterHolderHandle for transfer legs the holder on the other leg, {@code null} for
 *     non-transfers
 * @param intraAccount {@code true} for holder rebookings (both legs on this account — custody
 *     moved, the balance did not; REQ-BANK-011)
 * @param transferFee the in-game transfer fee added on top of this transaction's entered amount and
 *     borne by the debited source (ADR-0052, REQ-BANK-033); {@code 0} for non-fee rows. On an
 *     outgoing leg (amount &lt; 0) the leg is the gross debited, so the recipient received {@code
 *     |amount| − transferFee}
 * @param counterpartyHandle for a {@code DEPOSIT}/{@code WITHDRAWAL} the recorded counterparty's
 *     handle — the Einzahler / Empf&auml;nger on the far side (REQ-BANK-044), {@code null}
 *     otherwise or when none was recorded
 * @param counterpartyOrgUnitName the counterparty's org-unit name, or {@code null} when no
 *     counterparty or no org unit was recorded
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

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
 * Frontend mirror of one booking-history row (K1 page): this account's ledger leg with its
 * transaction context and — for transfers — the resolved counter side.
 *
 * @param postingId the leg's id
 * @param transactionId the owning transaction's id (the reversal target)
 * @param type transaction type enum name ({@code DEPOSIT}, {@code WITHDRAWAL}, {@code TRANSFER},
 *     {@code WIPE_RESET}, {@code REVERSAL})
 * @param amount the signed amount of this account's leg
 * @param holderHandle the holder whose stash this leg changed
 * @param note the transaction's free-text note, may be {@code null}
 * @param justification the transaction's free-text justification (Begr&uuml;ndung) for a {@code
 *     WITHDRAWAL} / {@code TRANSFER} (REQ-BANK-045), may be {@code null}
 * @param staffNote the booking bank employee's own note ("Notiz Bankmitarbeiter", REQ-BANK-054);
 *     always {@code null} on an org-unit member-facing row, where the backend redacts it as an
 *     internal remark (REQ-BANK-038)
 * @param createdAt the booking instant (UTC)
 * @param reversedTransactionId for reversal rows the corrected transaction's id, else {@code null}
 * @param counterAccountNo for transfer legs the other account's number, {@code null} otherwise
 * @param counterAccountName for transfer legs the other account's name
 * @param counterHolderHandle for transfer legs the holder on the other leg
 * @param intraAccount {@code true} for intra-account holder rebookings (custody moved, the balance
 *     did not)
 * @param transferFee the in-game transfer fee added on top of this transaction's entered amount and
 *     borne by the debited source (ADR-0052, REQ-BANK-033); {@code 0} for non-fee rows. On an
 *     outgoing leg the leg is the gross debited, so the recipient received {@code |amount| −
 *     transferFee}
 * @param counterpartyHandle for a {@code DEPOSIT}/{@code WITHDRAWAL} the recorded counterparty's
 *     handle — the Einzahler / Empf&auml;nger on the far side (REQ-BANK-044), {@code null}
 *     otherwise
 * @param counterpartyOrgUnitName the counterparty's org-unit name, or {@code null} when none was
 *     recorded
 */
public record BankBookingDto(
    UUID postingId,
    UUID transactionId,
    @BackendEnumAsString String type,
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

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

package de.greluc.krt.profit.basetool.backend.model.projection;

import de.greluc.krt.profit.basetool.backend.model.BankTransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * JPQL projection of one booking-history row on the account detail page: the account posting joined
 * with its transaction header (REQ-BANK-018). Holder and counter account are resolved separately in
 * batch.
 *
 * @param postingId the leg's id (stable row identity for the page)
 * @param transactionId the owning transaction's id (reversal target, counter-leg lookup key)
 * @param type the transaction type driving the row's chip rendering
 * @param amount the signed amount of this account's leg
 * @param note the transaction's free-text note, may be {@code null}
 * @param justification the Begr&uuml;ndung of a {@code WITHDRAWAL} / {@code TRANSFER}
 *     (REQ-BANK-045), {@code null} otherwise
 * @param staffNote the booking bank employee's note (REQ-BANK-054); {@code null} when none, and
 *     always {@code null} on an org-unit member-facing row (REQ-BANK-038)
 * @param createdAt the booking instant (UTC)
 * @param reversedTransactionId for {@code REVERSAL} rows the corrected transaction's id, else
 *     {@code null}
 * @param transferFee the in-game transfer fee borne by the debited source (REQ-BANK-033); {@code 0}
 *     for non-fee transactions and same-holder transfers
 * @param counterpartyHandle for a {@code DEPOSIT}/{@code WITHDRAWAL} the counterparty's handle
 *     snapshot (REQ-BANK-044), else {@code null}
 * @param counterpartyOrgUnitName the counterparty's org-unit name snapshot, or {@code null}
 */
public record BankBookingRow(
    UUID postingId,
    UUID transactionId,
    BankTransactionType type,
    BigDecimal amount,
    String note,
    String justification,
    String staffNote,
    Instant createdAt,
    UUID reversedTransactionId,
    BigDecimal transferFee,
    String counterpartyHandle,
    String counterpartyOrgUnitName) {}

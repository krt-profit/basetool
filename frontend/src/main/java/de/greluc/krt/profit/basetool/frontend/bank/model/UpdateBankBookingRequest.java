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

package de.greluc.krt.profit.basetool.frontend.bank.model;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Write payload for correcting one's own still-pending booking request (REQ-BANK-056).
 *
 * @param amount the corrected whole-aUEC amount
 * @param note the corrected note, or {@code null}
 * @param justification the corrected Begründung, or {@code null}
 * @param targetAccountId the corrected transfer destination, or {@code null}
 * @param counterpartyUserId the corrected withdrawal Empfänger, or {@code null}
 * @param counterpartyOrgUnitId that Empfänger's org unit, or {@code null}
 * @param version the request version the client read; {@code null} counts as {@code 0}
 */
public record UpdateBankBookingRequest(
    BigDecimal amount,
    String note,
    String justification,
    UUID targetAccountId,
    UUID counterpartyUserId,
    UUID counterpartyOrgUnitId,
    Long version) {}

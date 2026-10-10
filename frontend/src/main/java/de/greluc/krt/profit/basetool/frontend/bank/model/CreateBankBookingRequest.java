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

import de.greluc.krt.profit.basetool.frontend.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Write payload for raising a confirm-before-post booking request (REQ-BANK-022, REQ-BANK-055).
 *
 * @param sourceAccountId the account the request acts on
 * @param type the movement kind constant
 * @param targetAccountId the destination of a transfer, or {@code null}
 * @param amount whole-aUEC amount
 * @param note free-text note, or {@code null}
 * @param justification the Begründung, or {@code null}
 * @param splitEnabled whether a deposit distributes a share across the squadron accounts; {@code
 *     null} counts as {@code false}
 * @param splitPercent the whole percent to distribute, or {@code null}
 * @param counterpartyUserId the member receiving a withdrawal payout, or {@code null}
 * @param counterpartyOrgUnitId that member's org unit, or {@code null}
 */
@DtoMirror
public record CreateBankBookingRequest(
    UUID sourceAccountId,
    @BackendEnumAsString String type,
    UUID targetAccountId,
    BigDecimal amount,
    String note,
    String justification,
    Boolean splitEnabled,
    BigDecimal splitPercent,
    UUID counterpartyUserId,
    UUID counterpartyOrgUnitId) {}

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

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Write payload for booking a deposit (REQ-BANK-004, REQ-BANK-043, REQ-BANK-044).
 *
 * @param accountId the receiving account
 * @param holderId the player who received the money
 * @param amount whole-aUEC amount
 * @param note free-text note, or {@code null}
 * @param staffNote internal note of the booking employee, or {@code null}
 * @param splitEnabled whether a share is distributed across the squadron accounts; {@code null}
 *     counts as {@code false}
 * @param splitPercent the whole percent to distribute, or {@code null}
 * @param counterpartyUserId registered Einzahler, or {@code null}
 * @param counterpartyOrgUnitId org unit of the Einzahler, or {@code null}
 * @param counterpartyExternalName Einzahler without an account, or {@code null}
 */
@DtoMirror
public record BankDepositRequest(
    UUID accountId,
    UUID holderId,
    BigDecimal amount,
    String note,
    String staffNote,
    Boolean splitEnabled,
    BigDecimal splitPercent,
    UUID counterpartyUserId,
    UUID counterpartyOrgUnitId,
    String counterpartyExternalName) {}

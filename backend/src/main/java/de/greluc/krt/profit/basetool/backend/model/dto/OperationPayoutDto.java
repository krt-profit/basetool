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

import de.greluc.krt.profit.basetool.backend.model.PayoutPreference;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One participant's row of the operation payout breakdown.
 *
 * <p>Personal expenses are reimbursed first; the remaining pool is split by attendance share among
 * PAYOUT participants, while a DONATE participant's share goes to the org as {@link
 * #donatedAmount}. A 0.5% transfer fee is deducted from the gross payout, and {@link #payoutAmount}
 * is rounded HALF_UP to whole aUEC. The {@code paidOut*} fields are empty while no paid-out status
 * row exists.
 *
 * @param participantId opaque key: user UUID, {@code "guest_<name>"}, or {@code
 *     "deleted_<participantId>"} for a hard-deleted account (REQ-DATA-008)
 * @param participantName the display name, or {@code null} exactly when the account was deleted
 * @param participationPercentage clamped attendance-time share, 0–100, two decimals
 * @param payoutPreference {@code PAYOUT} or {@code DONATE} (sticky DONATE across the operation)
 * @param personalExpenses out-of-pocket expenses attributable to the participant; always &gt;= 0
 * @param shareAmount totalSum × percentage / 100 for PAYOUT, {@link BigDecimal#ZERO} for DONATE
 * @param donatedAmount the share a DONATE participant contributes, {@link BigDecimal#ZERO} for
 *     PAYOUT
 * @param transferFee 0.5% of {@code personalExpenses + shareAmount}; always &gt;= 0
 * @param payoutAmount {@code round(personalExpenses + shareAmount - transferFee)}, the net amount
 *     in whole aUEC
 * @param paidOut whether the mission manager has marked this participant as paid
 * @param paidOutAt timestamp of the last paid-out transition, or {@code null} when never set
 * @param paidOutByName effective name of the user who flipped the flag, or {@code null}
 */
public record OperationPayoutDto(
    String participantId,
    String participantName,
    double participationPercentage,
    PayoutPreference payoutPreference,
    BigDecimal personalExpenses,
    BigDecimal shareAmount,
    BigDecimal donatedAmount,
    BigDecimal transferFee,
    BigDecimal payoutAmount,
    boolean paidOut,
    Instant paidOutAt,
    String paidOutByName) {}

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

package de.greluc.krt.profit.basetool.frontend.operation.model;

import de.greluc.krt.profit.basetool.frontend.model.PayoutPreference;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Frontend mirror of the backend {@code OperationPayoutDto} returned by {@code
 * /api/v1/operations/{id}/payouts}. Carries the time-share percentage, the computed money number
 * for the participant ({@code personalExpenses} reimbursement + {@code shareAmount} pool split
 * minus the in-game {@code transferFee}, rounded HALF_UP to whole aUEC), and the
 * mission-manager-set paid-out audit fields.
 *
 * @param participantId opaque participant key — user UUID stringified or {@code "guest_<name>"}
 * @param participantName the participant's display name (for the table column)
 * @param participationPercentage clamped attendance-time share, 0–100, two decimals
 * @param payoutPreference {@code PAYOUT} or {@code DONATE} (sticky DONATE across the operation)
 * @param personalExpenses out-of-pocket expenses attributable to the participant (&gt;= 0)
 * @param shareAmount totalSum × percentage / 100 for PAYOUT, or zero for DONATE
 * @param donatedAmount the share a DONATE participant contributes to the org (totalSum × percentage
 *     / 100), or zero for PAYOUT; aggregated into {@code OperationPayoutSummaryDto.totalDonations}
 * @param transferFee 0.5% of {@code personalExpenses + shareAmount}, deducted to cover the in-game
 *     aUEC transfer fee — always &gt;= 0 and zero when the gross payout is zero
 * @param payoutAmount {@code round(personalExpenses + shareAmount - transferFee)} (HALF_UP to scale
 *     0) — pre-computed for display, i.e. the net amount in whole aUEC the participant actually
 *     receives in-game
 * @param paidOut whether the mission manager has marked this participant as already paid
 * @param paidOutAt timestamp of the last paid-out transition ({@code null} when never set)
 * @param paidOutByName effective name of the auditor that flipped the flag, or {@code null}
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

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
 * Data transfer record carrying one participant's row of the operation payout breakdown.
 *
 * <p>The amount fields obey the operation payout model: a participant's expenses (mission EXPENSE
 * finance entries where they are the participant + their refinery orders' {@code expenses +
 * otherExpenses}) are reimbursed off the top, and the remaining {@code totalSum} is split per
 * {@link #participationPercentage} across PAYOUT participants. DONATE participants keep their
 * {@link #personalExpenses} reimbursement (it is their own money) but contribute their share to the
 * org: that contributed share is exposed as {@link #donatedAmount} (and aggregated into {@code
 * OperationPayoutSummaryDto#totalDonations()}), while their {@link #shareAmount} stays {@link
 * BigDecimal#ZERO} so it is never paid out to them. From the resulting gross payout ({@code
 * personalExpenses + shareAmount}) a flat 0.5% in-game transfer fee ({@link #transferFee}) is
 * deducted to cover the Star Citizen banking overhead charged on every aUEC transfer to the
 * recipient. {@link #payoutAmount} is always {@code round(personalExpenses + shareAmount -
 * transferFee)} (HALF_UP to scale 0 — whole aUEC, since mobiGlas transfers do not accept fractional
 * credits) so the frontend can render a single number without re-deriving it.
 *
 * <p>The {@code paidOut*} block reflects the {@link
 * de.greluc.krt.profit.basetool.backend.model.OperationPayoutStatus} row that the mission-manager
 * toggle endpoint maintains: {@code paidOut=false}, no timestamp and no auditor name when no row
 * exists yet. {@link #paidOutByName} resolves to {@code User.effectiveName} or {@code null} when
 * the auditor has been deleted.
 *
 * @param participantId opaque participant key — user UUID stringified, {@code "guest_<name>"}, or
 *     {@code "deleted_<participantId>"} once the account behind the row was hard-deleted
 *     (REQ-DATA-008). The last form keeps a deleted member in the breakdown with a stable identity
 *     so an already-settled operation's shares do not silently redistribute.
 * @param participantName the participant's display name (for the table column), or {@code null}
 *     exactly when the account was deleted — a guest always carries a guest name and a linked user
 *     always an effective name, so the client renders its deleted-user placeholder off this null
 * @param participationPercentage clamped attendance-time share, 0–100, two decimals
 * @param payoutPreference {@code PAYOUT} or {@code DONATE} (sticky DONATE across the operation)
 * @param personalExpenses out-of-pocket expenses attributable to the participant (always &gt;= 0)
 * @param shareAmount totalSum × percentage / 100 for PAYOUT, or {@link BigDecimal#ZERO} for DONATE
 * @param donatedAmount the share a DONATE participant contributes to the org (totalSum × percentage
 *     / 100), or {@link BigDecimal#ZERO} for PAYOUT; aggregated into {@code
 *     OperationPayoutSummaryDto#totalDonations()}
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

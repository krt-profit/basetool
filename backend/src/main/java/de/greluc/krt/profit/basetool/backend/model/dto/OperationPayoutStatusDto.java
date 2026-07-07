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

import java.time.Instant;

/**
 * Minimal response of the operation payout paid-out toggle ({@code PUT
 * /api/v1/operations/{id}/payouts/paid-out}): just the paid-out flag block for the one participant
 * whose row changed.
 *
 * <p>A paid-out toggle only flips {@code paidOut} (and refreshes the audit trace) — it never
 * changes the participant's percentage or any amount, which the client already has rendered. So the
 * toggle returns only these fields and the client patches the single "Bezahlt" cell in place,
 * rather than re-running the full per-participant payout computation (the double finance/refinery
 * ledger load-all + the money math) just to hand back one row (#1121, the operation-side ADR-0078
 * gap). The field names match the {@link OperationPayoutDto} paid-out block so the frontend patch
 * logic is shared.
 *
 * @param participantKey opaque participant key — user UUID stringified or {@code "guest_<name>"}
 * @param paidOut whether the mission manager has marked this participant as already paid
 * @param paidOutAt timestamp of the last paid-out transition ({@code null} when never set)
 * @param paidOutByName effective name of the auditor that flipped the flag, or {@code null}
 */
public record OperationPayoutStatusDto(
    String participantKey, boolean paidOut, Instant paidOutAt, String paidOutByName) {}

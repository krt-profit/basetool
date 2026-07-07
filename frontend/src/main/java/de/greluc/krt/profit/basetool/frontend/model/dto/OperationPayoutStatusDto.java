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

import java.time.Instant;

/**
 * Frontend mirror of the backend {@code OperationPayoutStatusDto}: the minimal paid-out status
 * block the payout toggle returns. The operation-detail JS patches the single "Bezahlt" cell from
 * these fields — a toggle never changes any amount, so the backend does not re-run the payout
 * computation (#1121).
 *
 * @param participantKey opaque participant key — user UUID stringified or {@code "guest_<name>"}
 * @param paidOut whether the mission manager has marked this participant as already paid
 * @param paidOutAt timestamp of the last paid-out transition ({@code null} when never set)
 * @param paidOutByName effective name of the auditor that flipped the flag, or {@code null}
 */
public record OperationPayoutStatusDto(
    String participantKey, boolean paidOut, Instant paidOutAt, String paidOutByName) {}

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
 * Response of the operation payout paid-out toggle ({@code PUT
 * /api/v1/operations/{id}/payouts/paid-out}): the paid-out fields of the one changed participant,
 * named as in {@link OperationPayoutDto}.
 *
 * @param participantKey opaque participant key: user UUID or {@code "guest_<name>"}
 * @param paidOut whether the mission manager has marked this participant as paid
 * @param paidOutAt timestamp of the last paid-out transition, or {@code null} when never set
 * @param paidOutByName effective name of the user who flipped the flag, or {@code null}
 */
public record OperationPayoutStatusDto(
    String participantKey, boolean paidOut, Instant paidOutAt, String paidOutByName) {}

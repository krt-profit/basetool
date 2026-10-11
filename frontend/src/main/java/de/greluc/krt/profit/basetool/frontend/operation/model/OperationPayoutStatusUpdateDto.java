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

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;

/**
 * Frontend mirror of the backend write payload for the operation payout-status toggle. Carries the
 * opaque participant key and the new flag value the user clicked, forwarded verbatim by the page
 * controller to {@code PUT /api/v1/operations/{id}/payouts/paid-out}.
 *
 * @param participantKey opaque participant key from {@code OperationPayoutDto.participantId}
 * @param paidOut new value for the paid-out flag
 */
@DtoMirror
public record OperationPayoutStatusUpdateDto(String participantKey, boolean paidOut) {}

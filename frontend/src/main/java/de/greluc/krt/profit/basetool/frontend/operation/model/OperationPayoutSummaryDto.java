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
import java.math.BigDecimal;
import java.util.List;

/**
 * Frontend mirror of the backend {@code OperationPayoutSummaryDto} returned by {@code
 * /api/v1/operations/{id}/payouts}. Wraps the per-participant {@link OperationPayoutDto} rows with
 * the operation-wide donation total ({@code totalDonations} = sum of every row's {@code
 * donatedAmount}). The total is shown centrally on the operation-detail page; donations are
 * retained by the org and never redistributed to PAYOUT participants.
 *
 * @param totalDonations operation-wide sum of donated shares (always &gt;= 0); zero when nobody
 *     donated
 * @param payouts the per-participant payout rows, sorted by participant name
 */
@DtoMirror
public record OperationPayoutSummaryDto(
    BigDecimal totalDonations, List<OperationPayoutDto> payouts) {}

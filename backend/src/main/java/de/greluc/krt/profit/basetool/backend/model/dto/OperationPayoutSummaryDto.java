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

import java.math.BigDecimal;
import java.util.List;

/**
 * Payout view of an operation: the per-participant breakdown plus the operation-wide donation
 * total, which always equals the sum of the rows' {@link OperationPayoutDto#donatedAmount()}.
 *
 * @param totalDonations sum of donated shares, two-decimal scale; {@link BigDecimal#ZERO} when no
 *     participant donated
 * @param payouts the per-participant rows, sorted by participant name
 */
public record OperationPayoutSummaryDto(
    BigDecimal totalDonations, List<OperationPayoutDto> payouts) {}

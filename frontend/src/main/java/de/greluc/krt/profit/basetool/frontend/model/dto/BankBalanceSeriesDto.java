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

import java.math.BigDecimal;
import java.util.List;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the account balance-over-time series (REQ-BANK-049): the end-of-bucket running
 * balance points over the chosen period plus the account's balance target, scaled by {@code
 * BankBalanceChart} into the detail page's inline SVG line chart.
 *
 * @param points the balance points, oldest first (possibly empty)
 * @param balanceTarget the account's balance target (REQ-BANK-036) drawn as the reference line, or
 *     {@code null} when no target is set
 */
public record BankBalanceSeriesDto(
    List<BankBalancePointDto> points, @Nullable BigDecimal balanceTarget) {}

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

/**
 * Frontend mirror of the management-only dashboard totals strip.
 *
 * @param totalBalance sum of all account balances
 * @param inflow30d sum of all positive postings in the last 30 days
 * @param outflow30d sum of all negative postings in the last 30 days (negative or zero)
 * @param activeAccounts number of active accounts
 * @param closedAccounts number of closed accounts
 */
public record BankDashboardTotalsDto(
    BigDecimal totalBalance,
    BigDecimal inflow30d,
    BigDecimal outflow30d,
    long activeAccounts,
    long closedAccounts) {}

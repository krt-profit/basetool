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

/**
 * Aggregated totals for a mission's finance summary strip, served by {@code GET
 * /api/v1/missions/{id}/finance-entries/summary} and computed by a single SQL aggregate.
 *
 * <p>The {@code expense*} figures include refinery-order expenses, while {@code total} includes
 * refinery profit and equals the {@code /finance-entries/sum} value. Carries no participant PII.
 *
 * @param total signed bottom line: finance income − finance expense + refinery profit
 * @param incomeSum sum of all {@code INCOME} entries; never {@code null}, 0 when none
 * @param incomeCount number of {@code INCOME} entries
 * @param expenseSum sum of all {@code EXPENSE} entries plus refinery-order expenses; never {@code
 *     null}, 0 when none
 * @param expenseCount number of {@code EXPENSE} entries plus refinery orders with an expense
 */
public record MissionFinanceTotalsDto(
    BigDecimal total,
    BigDecimal incomeSum,
    long incomeCount,
    BigDecimal expenseSum,
    long expenseCount) {}

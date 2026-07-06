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
 * Frontend mirror of the backend {@code MissionFinanceTotalsDto}: the aggregated totals for a
 * mission's "Finanzen" summary strip, fetched from {@code
 * /api/v1/missions/{id}/finance-entries/summary}.
 *
 * <p>It replaces the previous "fetch the whole ledger ({@code size=1000}) and sum it in the page
 * controller" pattern with a single backend SQL aggregate (ADR-0078 mission-scale hardening), so a
 * finance render no longer materializes every ledger row. Distinct from {@link
 * MissionFinanceSummaryDto}, which is the operation-rollup per-mission summary carrying full lists.
 *
 * @param total signed mission bottom line (finance income − expense + refinery profit)
 * @param incomeSum summed amount of all income finance entries
 * @param incomeCount number of income finance entries
 * @param expenseSum summed amount of all expense finance entries plus refinery-order expenses
 * @param expenseCount number of expense finance entries plus refinery orders carrying an expense
 */
public record MissionFinanceTotalsDto(
    BigDecimal total,
    BigDecimal incomeSum,
    long incomeCount,
    BigDecimal expenseSum,
    long expenseCount) {}

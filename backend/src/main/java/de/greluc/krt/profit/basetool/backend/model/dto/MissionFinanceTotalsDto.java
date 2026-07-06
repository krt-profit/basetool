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
 * Aggregated totals for a mission's "Finanzen" summary strip (Gesamtsumme / Einnahmen / Ausgaben /
 * je Anteil), served by {@code GET /api/v1/missions/{id}/finance-entries/summary}.
 *
 * <p>Distinct from {@link MissionFinanceSummaryDto}, which is the operation-rollup per-mission
 * summary carrying the full entry + refinery lists. This record carries ONLY sums and counts: it
 * replaces the previous "fetch the whole ledger ({@code size=1000}) and sum it in the frontend"
 * pattern with a single SQL aggregate (ADR-0078 mission-scale hardening), so the strip's per-render
 * cost no longer scales with the number of ledger entries. Carries no participant PII, so it needs
 * no redaction. The {@code expense*} figures fold in refinery orders' raw expenses (matching the
 * legacy strip), while {@code total} folds in refinery <em>profit</em> (sales − expenses − other),
 * identical to the {@code /finance-entries/sum} value.
 *
 * @param total signed mission bottom line: finance income − finance expense + refinery profit
 * @param incomeSum summed amount of all {@code INCOME} finance entries (never {@code null}; 0 when
 *     none)
 * @param incomeCount number of {@code INCOME} finance entries
 * @param expenseSum summed amount of all {@code EXPENSE} finance entries plus refinery-order
 *     expenses (never {@code null}; 0 when none)
 * @param expenseCount number of {@code EXPENSE} finance entries plus refinery orders carrying an
 *     expense
 */
public record MissionFinanceTotalsDto(
    BigDecimal total,
    BigDecimal incomeSum,
    long incomeCount,
    BigDecimal expenseSum,
    long expenseCount) {}

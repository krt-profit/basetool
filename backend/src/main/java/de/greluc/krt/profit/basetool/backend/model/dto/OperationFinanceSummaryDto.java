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
import java.util.UUID;

/**
 * Operation finance roll-up served by {@code GET /api/v1/operations/{id}/finance-summary}: the
 * operation-wide total plus one total line per mission, computed from grouped SQL aggregates.
 *
 * <p>At most {@code OperationFinanceService.MAX_FINANCE_SUMMARY_MISSIONS} mission lines are
 * returned; {@link #truncated} flags a clipped list, and the total then covers only the returned
 * lines. Carries no participant PII.
 *
 * @param operationId the operation the roll-up belongs to
 * @param totalSum signed bottom line, the sum of the returned mission totals
 * @param missions per-mission lines, capped, ordered by mission name
 * @param truncated {@code true} when the mission list was clipped at the cap
 */
public record OperationFinanceSummaryDto(
    UUID operationId,
    BigDecimal totalSum,
    List<OperationMissionFinanceDto> missions,
    boolean truncated) {}

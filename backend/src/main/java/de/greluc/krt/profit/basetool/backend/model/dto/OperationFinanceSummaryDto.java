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
 * Lightweight operation finance roll-up served by {@code GET
 * /api/v1/operations/{id}/finance-summary}: the operation-wide total plus a per-mission total line,
 * computed from grouped SQL aggregates instead of the full {@link OperationFinanceDto} ledger
 * load-all.
 *
 * <p>This is the operation-side mirror of the mission finance summary aggregate (ADR-0078): the
 * operation-detail page renders the "Ergebnis je Einsatz" bars and the Gesamtergebnis from this
 * DTO, and lazy-loads each mission's per-entry breakdown on demand via {@code GET
 * /api/v1/operations/{id}/finances/{missionId}} — so the finance render no longer scans and
 * materializes every finance entry / refinery order across every child mission under a held Hikari
 * connection (#1121). The per-mission breakdown is capped: at most {@code
 * OperationFinanceService.MAX_FINANCE_SUMMARY_MISSIONS} mission lines are returned and {@link
 * #truncated} is set when the operation has more (the Gesamtergebnis then sums only the returned
 * lines). Carries no participant PII, so it needs no redaction.
 *
 * @param operationId the operation the roll-up belongs to
 * @param totalSum operation-wide signed bottom line — the sum of the returned mission totals
 * @param missions per-mission roll-up lines (id + name + total), capped, ordered by mission name
 * @param truncated {@code true} when the operation has more missions than the cap and the breakdown
 *     was clipped
 */
public record OperationFinanceSummaryDto(
    UUID operationId,
    BigDecimal totalSum,
    List<OperationMissionFinanceDto> missions,
    boolean truncated) {}

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
import java.util.UUID;

/**
 * Frontend mirror of the backend {@code OperationFinanceSummaryDto}: the lightweight operation
 * finance roll-up (operation-wide total + one total line per mission) that drives the
 * operation-detail "Ergebnis je Einsatz" bars and the Gesamtergebnis. Each mission's per-entry
 * breakdown loads lazily via {@code GET /operations/{id}/finance/{missionId}} (#1121).
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

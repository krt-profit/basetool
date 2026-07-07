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
import java.util.UUID;

/**
 * One mission's roll-up line in the operation finance summary: the mission's id + name and its
 * signed bottom line (finance income − expense + refinery profit), computed by grouped SQL
 * aggregates rather than a per-row load-all.
 *
 * <p>Unlike {@link MissionFinanceSummaryDto} this carries <em>no</em> per-entry /
 * per-refinery-order lists — those load lazily per mission via {@code GET
 * /api/v1/operations/{id}/finances/{missionId}} when the operation-detail finance panel expands a
 * mission's breakdown (#1121). This DTO drives the always-rendered "Ergebnis je Einsatz" bars and
 * each collapsed mission's summary line, so it stays cheap and connection-light regardless of how
 * many finance entries the operation's missions hold.
 *
 * @param missionId the mission's id (links the row to its lazy-loaded detail)
 * @param missionName the mission's display name
 * @param totalSum the mission's signed bottom line in aUEC (income − expense + refinery profit)
 */
public record OperationMissionFinanceDto(UUID missionId, String missionName, BigDecimal totalSum) {}

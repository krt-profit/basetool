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
 * One mission's line in the operation finance summary: its id, name and signed bottom line, without
 * per-entry lists (those load via {@code GET /api/v1/operations/{id}/finances/{missionId}}).
 *
 * @param missionId the mission's id
 * @param missionName the mission's display name
 * @param totalSum the mission's signed bottom line in aUEC (income − expense + refinery profit)
 */
public record OperationMissionFinanceDto(UUID missionId, String missionName, BigDecimal totalSum) {}

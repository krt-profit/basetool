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

package de.greluc.krt.profit.basetool.backend.repository;

import java.util.UUID;

/**
 * Per-mission refinery profit/loss aggregate produced by {@link
 * RefineryOrderRepository#aggregateProfitByMissionIds}: the sum of {@code oreSales − expenses −
 * otherExpenses} (null fields as 0) across one mission's refinery orders.
 *
 * @param missionId the mission the sum belongs to (the {@code GROUP BY} key)
 * @param profitSum the summed profit, or {@code null} when the mission has no refinery order;
 *     callers coalesce to zero
 */
public record RefineryMissionProfitAggregate(UUID missionId, Double profitSum) {}

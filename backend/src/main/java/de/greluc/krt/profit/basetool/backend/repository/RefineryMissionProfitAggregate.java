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
 * RefineryOrderRepository#aggregateProfitByMissionIds} via a grouped JPQL constructor expression —
 * one row per mission, {@code SUM}med across the mission's refinery orders in a single query.
 *
 * <p>Each order contributes {@code oreSales − expenses − otherExpenses} (legacy null fields
 * coalesced to 0, identical to the in-memory roll-up it replaces). It exists so the operation
 * finance roll-up folds refinery profit into each mission's total without materializing every
 * refinery-order row across every child mission (the operation-side ADR-0078 gap, #1121). A SQL
 * {@code SUM} over an empty set is {@code NULL}, so {@link #profitSum} may be {@code null} when a
 * mission has no refinery order; the caller coalesces to zero.
 *
 * @param missionId the mission the sum belongs to (the {@code GROUP BY} key)
 * @param profitSum summed {@code oreSales − expenses − otherExpenses} across the mission's refinery
 *     orders, or {@code null} when the mission has none
 */
public record RefineryMissionProfitAggregate(UUID missionId, Double profitSum) {}

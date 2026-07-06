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

import java.math.BigDecimal;

/**
 * Single-query per-type aggregate over a mission's finance entries, produced by {@link
 * MissionFinanceEntryRepository#aggregateFinanceByMission} via a JPQL constructor expression.
 *
 * <p>It exists so the finance summary strip and the total-sum endpoint compute their numbers with
 * one grouped SQL query instead of materializing every finance-entry row: under the multi-user
 * mission-page live-update fan-out (ADR-0078) the previous {@code size=1000} load-all pinned a
 * database connection per render and, at 200 viewers, starved the pool. The sums are {@code null}
 * when the mission has no entry of that type (SQL {@code SUM} over an empty set is {@code NULL});
 * the caller coalesces them to zero.
 *
 * @param incomeSum summed amount of all {@code INCOME} entries, or {@code null} when there are none
 * @param incomeCount number of {@code INCOME} entries (never {@code null}; 0 when none)
 * @param expenseSum summed amount of all {@code EXPENSE} entries, or {@code null} when there are
 *     none
 * @param expenseCount number of {@code EXPENSE} entries (never {@code null}; 0 when none)
 */
public record FinanceEntryAggregate(
    BigDecimal incomeSum, Long incomeCount, BigDecimal expenseSum, Long expenseCount) {}

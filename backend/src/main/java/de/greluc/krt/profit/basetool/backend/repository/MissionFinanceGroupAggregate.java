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
import java.util.UUID;

/**
 * Per-mission income/expense sums produced by {@link
 * MissionFinanceEntryRepository#aggregateFinanceByMissionIds}.
 *
 * @param missionId the mission the sums belong to (the {@code GROUP BY} key)
 * @param incomeSum summed amount of the mission's {@code INCOME} entries, or {@code null} when none
 * @param expenseSum summed amount of the mission's {@code EXPENSE} entries, or {@code null} when
 *     none
 */
public record MissionFinanceGroupAggregate(
    UUID missionId, BigDecimal incomeSum, BigDecimal expenseSum) {}

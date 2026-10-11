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

package de.greluc.krt.profit.basetool.backend.mission.api;

import java.math.BigDecimal;
import org.jetbrains.annotations.NotNull;

/**
 * One contributor's share of a mission's finance totals.
 *
 * @param profit the signed profit added to the mission's bottom line
 * @param expenseSum the expenses added to the mission's expense total
 * @param expenseCount how many expense lines {@code expenseSum} stands for
 */
public record MissionFinanceContribution(
    @NotNull BigDecimal profit, @NotNull BigDecimal expenseSum, long expenseCount) {

  /** The contribution of a mission the contributor knows nothing of. */
  public static final MissionFinanceContribution NONE =
      new MissionFinanceContribution(BigDecimal.ZERO, BigDecimal.ZERO, 0L);
}

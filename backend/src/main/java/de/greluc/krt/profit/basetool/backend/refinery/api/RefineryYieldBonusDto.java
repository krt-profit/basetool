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

package de.greluc.krt.profit.basetool.backend.refinery.api;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The UEX yield bonus of one material at one refinery, in percent (positive = bonus, negative =
 * malus, {@code 0} = baseline), with its 7- and 30-day averages (REQ-REFINERY-026).
 *
 * @param percent the current yield bonus
 * @param weekAveragePercent the average over the last 7 days, or {@code null} when UEX has none
 * @param monthAveragePercent the average over the last 30 days, or {@code null} when UEX has none
 */
public record RefineryYieldBonusDto(
    @NotNull Integer percent,
    @Nullable Integer weekAveragePercent,
    @Nullable Integer monthAveragePercent) {}

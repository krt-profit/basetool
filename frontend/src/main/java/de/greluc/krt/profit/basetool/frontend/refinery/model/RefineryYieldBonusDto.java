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

package de.greluc.krt.profit.basetool.frontend.refinery.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;

/**
 * Frontend mirror of the UEX yield bonus of one material at one refinery, in percent, with its 7-
 * and 30-day averages (REQ-REFINERY-026).
 *
 * @param percent the current yield bonus (positive = bonus, negative = malus)
 * @param weekAveragePercent the average over the last 7 days, or {@code null} when UEX has none
 * @param monthAveragePercent the average over the last 30 days, or {@code null} when UEX has none
 */
@DtoMirror
public record RefineryYieldBonusDto(
    Integer percent, Integer weekAveragePercent, Integer monthAveragePercent) {}

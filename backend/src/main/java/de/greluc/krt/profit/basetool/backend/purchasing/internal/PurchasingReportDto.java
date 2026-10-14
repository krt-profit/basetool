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

package de.greluc.krt.profit.basetool.backend.purchasing.internal;

import java.util.List;

/**
 * The purchasing report of a period (REQ-PUR-023): per month, per material and per need, without
 * member names or partner handles.
 *
 * @param from the first month as {@code yyyy-MM}
 * @param to the last month as {@code yyyy-MM}
 * @param totals the totals of the period
 * @param months the months, oldest first
 * @param materials the materials and items, most bought first
 * @param needs the needs, most bought first
 */
public record PurchasingReportDto(
    String from,
    String to,
    ReportTotalsDto totals,
    List<ReportMonthDto> months,
    List<ReportMaterialDto> materials,
    List<ReportNeedDto> needs) {}

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

package de.greluc.krt.profit.basetool.backend.orgchart.internal;

import java.util.List;

/**
 * The Bereichsleitung tier at the top of the org chart. Holds the single Bereichsleiter plus the
 * open-ended groups of Commander, Bereichskoordinatoren and Bereichsoperatoren. Any list may be
 * empty, and {@link #lead} may be {@code null} when no Bereichsleiter is assigned yet.
 *
 * @param lead the Bereichsleiter node, or {@code null} when the seat is vacant.
 * @param commanders the area-leadership Commanders, ordered for display; never {@code null}.
 * @param coordinators the Bereichskoordinatoren, ordered for display; never {@code null}.
 * @param operators the Bereichsoperatoren, ordered for display; never {@code null}.
 */
public record AreaLeadershipDto(
    OrgChartNodeDto lead,
    List<OrgChartNodeDto> commanders,
    List<OrgChartNodeDto> coordinators,
    List<OrgChartNodeDto> operators) {}

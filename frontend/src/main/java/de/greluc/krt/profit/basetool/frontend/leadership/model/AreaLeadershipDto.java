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

package de.greluc.krt.profit.basetool.frontend.leadership.model;

import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;
import de.greluc.krt.profit.basetool.frontend.orgchart.model.OrgChartNodeDto;
import java.util.List;

/**
 * Frontend mirror of the Bereichsleitung tier. Any list may be empty and {@link #lead} may be
 * {@code null} when the Bereichsleiter seat is vacant.
 *
 * @param lead the Bereichsleiter node, or {@code null} when vacant.
 * @param commanders the area-leadership Commanders; never {@code null}.
 * @param coordinators the Bereichskoordinatoren; never {@code null}.
 * @param operators the Bereichsoperatoren; never {@code null}.
 */
@DtoMirror
public record AreaLeadershipDto(
    OrgChartNodeDto lead,
    List<OrgChartNodeDto> commanders,
    List<OrgChartNodeDto> coordinators,
    List<OrgChartNodeDto> operators) {}

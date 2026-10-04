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
import java.util.UUID;

/**
 * One Spezialkommando column in the org chart, led by one or two Commanders (SK-Leiter). SKs carry
 * no further sub-structure in the chart. Always rendered even when empty so admins can fill it in.
 *
 * @param orgUnitId id of the owning Spezialkommando.
 * @param name the SK's display name.
 * @param shorthand the SK's short tag.
 * @param commanders the SK-Leiter nodes, ordered for display; never {@code null}, at most two.
 * @param canAddCommander whether another SK-Leiter may still be added (fewer than two exist).
 */
public record SpecialCommandChartDto(
    UUID orgUnitId,
    String name,
    String shorthand,
    List<OrgChartNodeDto> commanders,
    boolean canAddCommander) {}

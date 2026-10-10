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

package de.greluc.krt.profit.basetool.frontend.orgchart.model;

import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;
import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of one Spezialkommando column, led by one or two SK-Leiter (Commander).
 *
 * @param orgUnitId id of the owning Spezialkommando.
 * @param name the SK's display name.
 * @param shorthand the SK's short tag.
 * @param commanders the SK-Leiter nodes; never {@code null}, at most two.
 * @param canAddCommander whether another SK-Leiter may still be added.
 */
@DtoMirror
public record SpecialCommandChartDto(
    UUID orgUnitId,
    String name,
    String shorthand,
    List<OrgChartNodeDto> commanders,
    boolean canAddCommander) {}

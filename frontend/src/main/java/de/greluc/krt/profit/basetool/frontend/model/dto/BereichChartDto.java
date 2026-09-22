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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.util.List;
import java.util.UUID;

/**
 * Frontend mirror of one Bereich tier of the multi-Bereich org chart (epic #692, REQ-ORG-026),
 * decoded from {@code GET /api/v1/org-chart}: the Bereich's Bereichsleitung sub-tree plus the
 * Staffeln/SKs reporting into it, tinted by the Bereich's department.
 *
 * <p>{@code department} is the department's enum name (e.g. {@code "PROFIT"}) or {@code null} when
 * unassigned; the template maps it to the {@code --color-dept-*} CSS token. The leadership reuses
 * {@link AreaLeadershipDto} (lead = Bereichsleiter, coordinators = Bereichskoordinatoren, operators
 * = Bereichsoperatoren; commanders always empty).
 *
 * @param orgUnitId the Bereich's org-unit id.
 * @param name the Bereich's display name.
 * @param shorthand the Bereich's short tag.
 * @param department the department / Bereichsfarbe enum name, or {@code null} when unassigned.
 * @param leadership the Bereichsleitung sub-tree.
 * @param squadrons the Bereich's Staffeln, ordered by name.
 * @param specialCommands the Bereich's Spezialkommandos, ordered by name.
 */
public record BereichChartDto(
    UUID orgUnitId,
    String name,
    String shorthand,
    @BackendEnumAsString String department,
    AreaLeadershipDto leadership,
    List<SquadronChartDto> squadrons,
    List<SpecialCommandChartDto> specialCommands) {}

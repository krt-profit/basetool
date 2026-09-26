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
 * Frontend mirror of one Bereich of the org chart (REQ-ORG-026): its Bereichsleitung and the
 * Staffeln and Spezialkommandos reporting into it.
 *
 * @param orgUnitId the Bereich's org-unit id.
 * @param name the Bereich's display name.
 * @param shorthand the Bereich's short tag.
 * @param department the department / Bereichsfarbe enum name, or {@code null} when unassigned.
 * @param leadership the Bereichsleitung sub-tree; its commanders list is always empty.
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

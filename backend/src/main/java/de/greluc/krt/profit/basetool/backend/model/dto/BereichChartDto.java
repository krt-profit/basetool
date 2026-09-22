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

package de.greluc.krt.profit.basetool.backend.model.dto;

import de.greluc.krt.profit.basetool.backend.model.Department;
import java.util.List;
import java.util.UUID;

/**
 * One Bereich tier of the multi-Bereich org chart (epic #692, REQ-ORG-026): its Bereichsleitung
 * sub-tree plus the Staffeln and Spezialkommandos that report into it, tinted by the Bereich's
 * frozen {@link Department Bereichsfarbe}.
 *
 * <p>The Bereichsleitung is carried as an {@link AreaLeadershipDto} (reused for layout symmetry
 * with the legacy area leadership): its {@code lead} is the Bereichsleiter, {@code coordinators}
 * the Bereichskoordinatoren and {@code operators} the Bereichsoperatoren; {@code commanders} is
 * always empty (a Bereich has no commander rank). The {@code squadrons} / {@code specialCommands}
 * are this Bereich's child units, reusing the existing {@link SquadronChartDto} / {@link
 * SpecialCommandChartDto} so the per-unit rendering (and the existing ARIA tree) is unchanged.
 *
 * @param orgUnitId the Bereich's org-unit id.
 * @param name the Bereich's display name.
 * @param shorthand the Bereich's short tag.
 * @param department the Bereich's department / Bereichsfarbe, or {@code null} when unassigned (the
 *     chart renders the Bereich untinted).
 * @param leadership the Bereichsleitung sub-tree (Bereichsleiter + koordinatoren + operatoren).
 * @param squadrons the Bereich's Staffeln, ordered by name.
 * @param specialCommands the Bereich's Spezialkommandos, ordered by name.
 */
public record BereichChartDto(
    UUID orgUnitId,
    String name,
    String shorthand,
    Department department,
    AreaLeadershipDto leadership,
    List<SquadronChartDto> squadrons,
    List<SpecialCommandChartDto> specialCommands) {}

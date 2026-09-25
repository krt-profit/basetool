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

import java.util.List;

/**
 * The complete org chart as one nested read model (REQ-ORG-026), returned by {@code GET
 * /api/v1/org-chart} to every authenticated user.
 *
 * @param organisationsleitung the OL tier, or {@code null} when no active OL exists
 * @param bereiche the per-Bereich tiers, ordered by name; never {@code null}, possibly empty
 * @param areaLeadership the ungrouped area-leadership tier; never {@code null}
 * @param squadrons the profit-eligible Staffeln without a Bereich parent, ordered by name; never
 *     {@code null}, possibly empty
 * @param specialCommands the profit-eligible Spezialkommandos without a Bereich parent, ordered by
 *     name; never {@code null}, possibly empty
 */
public record OrgChartDto(
    OlChartDto organisationsleitung,
    List<BereichChartDto> bereiche,
    AreaLeadershipDto areaLeadership,
    List<SquadronChartDto> squadrons,
    List<SpecialCommandChartDto> specialCommands) {}

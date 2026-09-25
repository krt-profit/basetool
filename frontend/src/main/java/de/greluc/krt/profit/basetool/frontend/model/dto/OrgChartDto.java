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

/**
 * Frontend mirror of the complete org chart, decoded from {@code GET /api/v1/org-chart}: the OL
 * tier, one tier per Bereich, and an ungrouped tier for units not placed under a Bereich
 * (REQ-ORG-026).
 *
 * @param organisationsleitung the OL tier (id + name + members), or {@code null} when no OL exists.
 * @param bereiche the per-Bereich tiers, ordered by name; never {@code null}, possibly empty.
 * @param areaLeadership the ungrouped area-leadership tier; never {@code null}.
 * @param squadrons the ungrouped profit-eligible Staffeln, ordered by name; never {@code null}.
 * @param specialCommands the ungrouped profit-eligible Spezialkommandos, ordered by name; never
 *     {@code null}.
 */
public record OrgChartDto(
    OlChartDto organisationsleitung,
    List<BereichChartDto> bereiche,
    AreaLeadershipDto areaLeadership,
    List<SquadronChartDto> squadrons,
    List<SpecialCommandChartDto> specialCommands) {}

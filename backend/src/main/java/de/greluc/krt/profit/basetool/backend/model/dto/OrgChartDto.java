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
 * The complete org chart as one nested read model. With epic #692 / REQ-ORG-026 it is
 * multi-Bereich:
 *
 * <ul>
 *   <li>{@code organisationsleitung} — the OL tier at the very top ({@code null} when no OL
 *       exists);
 *   <li>{@code bereiche} — one tier per Bereich (its Bereichsleitung sub-tree + the Staffeln/SKs
 *       that report into it), each tinted by its Bereichsfarbe;
 *   <li>{@code areaLeadership} + {@code squadrons} + {@code specialCommands} — the legacy/ungrouped
 *       tier: the singleton area leadership and every profit-eligible Staffel/SK <em>not</em> wired
 *       under a Bereich. Until an admin creates Bereiche/OL and assigns parents this is the whole
 *       chart, so the rendering degrades exactly to the pre-#692 single-tree view.
 * </ul>
 *
 * <p>Returned by {@code GET /api/v1/org-chart} to every authenticated user; the inline admin editor
 * mutates it one position at a time through the position endpoints.
 *
 * @param organisationsleitung the OL tier (id + name + OL_MEMBER nodes), or {@code null} when no
 *     active OL exists.
 * @param bereiche the per-Bereich tiers, ordered by name; never {@code null}, possibly empty.
 * @param areaLeadership the legacy/ungrouped area-leadership tier; never {@code null}.
 * @param squadrons the ungrouped profit-eligible Staffeln (no Bereich parent), ordered by name;
 *     never {@code null}, possibly empty.
 * @param specialCommands the ungrouped profit-eligible Spezialkommandos (no Bereich parent),
 *     ordered by name; never {@code null}, possibly empty.
 */
public record OrgChartDto(
    OlChartDto organisationsleitung,
    List<BereichChartDto> bereiche,
    AreaLeadershipDto areaLeadership,
    List<SquadronChartDto> squadrons,
    List<SpecialCommandChartDto> specialCommands) {}

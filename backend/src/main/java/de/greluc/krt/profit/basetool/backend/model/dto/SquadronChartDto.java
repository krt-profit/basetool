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
import java.util.UUID;

/**
 * One Staffel column in the org chart: its Staffelleiter, up to four Kommandos and the Ensigns
 * reporting directly to the Staffelleiter; rendered even when empty.
 *
 * @param orgUnitId id of the owning Staffel.
 * @param name the Staffel's display name.
 * @param shorthand the Staffel's short tag (e.g. {@code IRI}).
 * @param lead the Staffelleiter node, or {@code null} when the seat is vacant.
 * @param commands the Kommandos, ordered for display; never {@code null}, at most four.
 * @param directEnsigns Ensigns reporting straight to the Staffelleiter, ordered for display; never
 *     {@code null}.
 * @param canAddCommand whether another Kommandoleiter may still be added (fewer than four exist).
 * @param canAddEnsign whether another Ensign may still be added (fewer than four exist in total).
 */
public record SquadronChartDto(
    UUID orgUnitId,
    String name,
    String shorthand,
    OrgChartNodeDto lead,
    List<CommandChartDto> commands,
    List<OrgChartNodeDto> directEnsigns,
    boolean canAddCommand,
    boolean canAddEnsign) {}

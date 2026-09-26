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
 * Frontend mirror of the Leitung view from {@code GET /api/v1/leitung/view} (REQ-ROLE-004): the org
 * units the caller may appoint into, grouped by tier.
 *
 * @param admin whether the caller is an admin.
 * @param organisationsleitungen the OL(s) the caller may manage.
 * @param bereiche the Bereiche the caller may appoint into.
 * @param squadrons the Staffeln the caller may appoint into.
 * @param specialCommands the Spezialkommandos the caller may appoint a lead on or whose members
 *     they may manage.
 */
public record LeitungViewDto(
    boolean admin,
    List<LeitungUnitDto> organisationsleitungen,
    List<LeitungUnitDto> bereiche,
    List<LeitungUnitDto> squadrons,
    List<LeitungUnitDto> specialCommands) {}

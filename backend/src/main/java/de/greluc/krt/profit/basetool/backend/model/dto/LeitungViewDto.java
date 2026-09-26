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
 * The read-only delegated Leitung view: the org units the caller may act on, grouped by tier
 * (REQ-ROLE-004).
 *
 * <p>A plain member gets four empty lists.
 *
 * @param admin whether the caller is an admin; the per-unit caps already include this.
 * @param organisationsleitungen the Organisationsleitung(en) the caller may manage; never {@code
 *     null}.
 * @param bereiche the Bereiche the caller may appoint into; never {@code null}.
 * @param squadrons the Staffeln the caller may appoint into; never {@code null}.
 * @param specialCommands the Spezialkommandos the caller may appoint a lead on or manage the
 *     members of; never {@code null}.
 */
public record LeitungViewDto(
    boolean admin,
    List<LeitungUnitDto> organisationsleitungen,
    List<LeitungUnitDto> bereiche,
    List<LeitungUnitDto> squadrons,
    List<LeitungUnitDto> specialCommands) {}

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
 * The delegated Leitung view (epic #800, REQ-ROLE-004): the org units the caller may act on,
 * grouped by tier so the {@code /organisation/leitung} page can render one section per tier. Each
 * list holds only the units the caller's tier can appoint into (admin sees all); a plain member
 * gets four empty lists and the page shows its empty state. The view is read-only — every
 * appointment is a separate write through the Phase-3 appointment endpoints, each re-checking the
 * delegated authorisation.
 *
 * @param admin whether the caller is an admin (used by the page to widen affordances); the per-unit
 *     caps already fold the admin short-circuit in.
 * @param organisationsleitungen the Organisationsleitung(en) the caller may manage (admin: appoint
 *     OL members); never {@code null}.
 * @param bereiche the Bereiche the caller may appoint into; never {@code null}.
 * @param squadrons the Staffeln the caller may appoint into; never {@code null}.
 * @param specialCommands the Spezialkommandos the caller may appoint a lead on (admin, parent
 *     Bereichsleiter) or manage the members of (admin, the SK's own lead); never {@code null}.
 */
public record LeitungViewDto(
    boolean admin,
    List<LeitungUnitDto> organisationsleitungen,
    List<LeitungUnitDto> bereiche,
    List<LeitungUnitDto> squadrons,
    List<LeitungUnitDto> specialCommands) {}

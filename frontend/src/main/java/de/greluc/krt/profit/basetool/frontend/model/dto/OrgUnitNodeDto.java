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

import java.util.UUID;

/**
 * Frontend mirror of the backend {@code OrgUnitNodeDto}: one org unit with its parent edge and
 * version, for the admin org-structure page (REQ-ORG-014).
 *
 * @param id the org unit's id.
 * @param name the org unit's display name.
 * @param shorthand the org unit's short tag.
 * @param kind the org unit's kind name (SQUADRON / SPECIAL_COMMAND / BEREICH /
 *     ORGANISATIONSLEITUNG).
 * @param parentOrgUnitId the id of the current parent, or {@code null} if unparented / root.
 * @param department the Bereich's department name (Bereichsfarbe), or {@code null} for other kinds.
 * @param version the optimistic-lock version, required by the set-parent PATCH.
 */
public record OrgUnitNodeDto(
    UUID id,
    String name,
    String shorthand,
    @BackendEnumAsString String kind,
    UUID parentOrgUnitId,
    @BackendEnumAsString String department,
    Long version) {}

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
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import java.util.UUID;

/**
 * Flat hierarchy-node projection of an {@link de.greluc.krt.profit.basetool.backend.model.OrgUnit}
 * for the admin org-structure management surface (epic #692, REQ-ORG-014). Unlike the per-kind DTOs
 * ({@code SquadronDto} / {@code SpecialCommandDto} / {@code BereichDto}) — which each expose only
 * their own tier — this carries the three fields the management table needs uniformly across all
 * four kinds: the current {@code parentOrgUnitId} (where the unit sits today) and the
 * optimistic-lock {@code version} (so the UI can PATCH a new parent edge without a stale-version
 * 409). {@code department} is populated only for {@code BEREICH} rows (the Bereichsfarbe,
 * REQ-ORG-026) and {@code null} for every other kind.
 *
 * @param id the org unit's id.
 * @param name the org unit's display name.
 * @param shorthand the org unit's short tag.
 * @param kind the org unit's kind (SQUADRON / SPECIAL_COMMAND / BEREICH / ORGANISATIONSLEITUNG).
 * @param parentOrgUnitId the id of the unit's current parent, or {@code null} if unparented / root.
 * @param department the Bereich's department (Bereichsfarbe), or {@code null} for non-Bereich
 *     kinds.
 * @param version the optimistic-lock version, required by the set-parent PATCH.
 */
public record OrgUnitNodeDto(
    UUID id,
    String name,
    String shorthand,
    OrgUnitKind kind,
    UUID parentOrgUnitId,
    Department department,
    Long version) {}

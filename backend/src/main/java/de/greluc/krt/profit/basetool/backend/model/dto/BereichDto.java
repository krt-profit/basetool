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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Wire shape for {@link de.greluc.krt.profit.basetool.backend.model.Bereich} (epic #692,
 * REQ-ORG-014). Mirrors {@link SpecialCommandDto} but adds the {@code parentOrgUnitId} hierarchy
 * link (the owning {@code Organisationsleitung}) and omits the profit-eligibility flag — a Bereich
 * is never a Job-Order processor; only its Staffeln/SKs are. Promotion is omitted for the same
 * reason as on an SK (permanently disabled at the data layer).
 *
 * @param id Bereich identifier; {@code null} on create requests (the server stamps a fresh UUID),
 *     populated on responses.
 * @param name display name; case-insensitive unique across <em>all</em> org-unit kinds (the {@code
 *     org_unit.name} UNIQUE constraint spans every kind). Required, max 255 chars.
 * @param shorthand short tag for chips / the org chart; unique across all kinds. Required, max 255.
 * @param description free-form text; nullable.
 * @param active soft-delete flag; server-populated, {@code null} on requests means "no change".
 * @param parentOrgUnitId the owning {@code Organisationsleitung}'s id, or {@code null} when the
 *     Bereich is not yet wired under the OL. Set on create (optional) or via the dedicated
 *     set-parent endpoint; the parent kind is validated server-side (must be the OL).
 * @param department the Kartell department / frozen Bereichsfarbe (epic #692, REQ-ORG-026) used to
 *     tint this Bereich's nodes in the org chart; {@code null} when unassigned (the chart renders
 *     the Bereich untinted). Optional on create.
 * @param version optimistic-lock counter; server-populated on create + read, required on update.
 */
public record BereichDto(
    UUID id,
    @NotBlank @Size(max = 255) String name,
    @NotBlank @Size(max = 255) String shorthand,
    @Size(max = 65_535) String description,
    Boolean active,
    UUID parentOrgUnitId,
    Department department,
    Long version) {}

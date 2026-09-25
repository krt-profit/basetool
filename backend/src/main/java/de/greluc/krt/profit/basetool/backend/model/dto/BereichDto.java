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
 * Wire shape for {@link de.greluc.krt.profit.basetool.backend.model.Bereich} (REQ-ORG-014). Like
 * {@link SpecialCommandDto} plus the {@code parentOrgUnitId} link, without profit eligibility or
 * promotion.
 *
 * @param id Bereich identifier; {@code null} on create requests, populated on responses.
 * @param name display name; case-insensitive unique across all org-unit kinds; required, max 255.
 * @param shorthand short tag; unique across all kinds; required, max 255.
 * @param description free-form text; nullable.
 * @param active soft-delete flag; server-populated, {@code null} on requests means "no change".
 * @param parentOrgUnitId the owning {@code Organisationsleitung}'s id, or {@code null} when not yet
 *     wired; the parent kind is validated server-side.
 * @param department the Bereichsfarbe tinting the org chart (REQ-ORG-026), or {@code null} when
 *     unassigned.
 * @param version optimistic-lock counter; required on update.
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

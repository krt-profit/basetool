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
 * Flat hierarchy-node view of an {@link de.greluc.krt.profit.basetool.backend.model.OrgUnit} of any
 * kind for the admin org-structure page (REQ-ORG-014).
 *
 * @param id the org unit's id
 * @param name the org unit's display name
 * @param shorthand the org unit's short tag
 * @param kind the org unit's kind
 * @param parentOrgUnitId the current parent's id, or {@code null} for a root or unparented unit
 * @param department the Bereich's department (REQ-ORG-026), or {@code null} for other kinds
 * @param version the optimistic-lock version, required by the set-parent PATCH
 */
public record OrgUnitNodeDto(
    UUID id,
    String name,
    String shorthand,
    OrgUnitKind kind,
    UUID parentOrgUnitId,
    Department department,
    Long version) {}

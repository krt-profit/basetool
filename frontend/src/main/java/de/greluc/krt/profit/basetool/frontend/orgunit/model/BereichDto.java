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

package de.greluc.krt.profit.basetool.frontend.orgunit.model;

import de.greluc.krt.profit.basetool.frontend.model.BackendEnumAsString;
import java.util.UUID;

/**
 * A Bereich as the backend org-hierarchy API returns it (REQ-ORG-014).
 *
 * @param id the Bereich id
 * @param name the display name
 * @param shorthand the short name
 * @param description the description, or {@code null}
 * @param active whether the Bereich is active
 * @param parentOrgUnitId the Organisationsleitung above it, or {@code null}
 * @param department the backend {@code Department} name, or {@code null}
 * @param version the optimistic-lock version
 */
public record BereichDto(
    UUID id,
    String name,
    String shorthand,
    String description,
    Boolean active,
    UUID parentOrgUnitId,
    @BackendEnumAsString String department,
    Long version) {}

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

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;

/**
 * The backend's answer to a parent change of an org unit (REQ-ORG-014).
 *
 * @param orgUnitId the child org unit
 * @param kind the child's {@code OrgUnitKind} name
 * @param parentOrgUnitId the new parent, or {@code null} when detached
 * @param version the child's bumped optimistic-lock version
 */
@DtoMirror
public record OrgUnitParentResponse(
    UUID orgUnitId, @BackendEnumAsString String kind, UUID parentOrgUnitId, Long version) {}

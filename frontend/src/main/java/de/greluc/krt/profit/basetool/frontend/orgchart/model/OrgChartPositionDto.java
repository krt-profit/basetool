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

package de.greluc.krt.profit.basetool.frontend.orgchart.model;

import de.greluc.krt.profit.basetool.frontend.kernel.model.BackendEnumAsString;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import java.util.UUID;

/**
 * One org-chart seat as the backend returns it after a write.
 *
 * @param id the seat
 * @param positionType the functional-rank enum name
 * @param orgUnitId the org unit the seat belongs to, or {@code null}
 * @param userId the holding account, or {@code null}
 * @param userName the holding account's display name, or {@code null}
 * @param displayName the free-text holder, or {@code null}
 * @param name the seat name, or {@code null}
 * @param parentId the parent seat, or {@code null}
 * @param sortIndex the display order among its siblings
 * @param version the optimistic-lock version
 */
@DtoMirror
public record OrgChartPositionDto(
    UUID id,
    @BackendEnumAsString String positionType,
    UUID orgUnitId,
    UUID userId,
    String userName,
    String displayName,
    String name,
    UUID parentId,
    int sortIndex,
    Long version) {}

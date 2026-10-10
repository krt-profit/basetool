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

import de.greluc.krt.profit.basetool.frontend.model.BackendEnumAsString;
import java.util.UUID;

/**
 * Frontend mirror of the backend org-chart node — one filled functional-rank position. The {@code
 * positionType} is carried as the raw enum name (a {@link String}) rather than a duplicated enum,
 * so the template resolves its label via the {@code orgChart.rank.<TYPE>} message key and the
 * inline editor echoes it back unchanged.
 *
 * @param positionId id of the underlying position row; the handle every edit / remove action
 *     targets.
 * @param positionType the functional-rank enum name (e.g. {@code SQUADRON_LEAD}).
 * @param userId id of the user holding the position, or {@code null} when the holder is a free-text
 *     name (or the seat is vacant); preselects the reassign picker when present.
 * @param userName the holding account's effective display name, or {@code null} for a free-text
 *     holder or a vacant seat.
 * @param displayName the free-text holder name for a member without a Basetool account, or {@code
 *     null} when the holder is an account or the seat is vacant; mutually exclusive with {@code
 *     userId}.
 * @param sortIndex stable display order within the sibling group.
 * @param version optimistic-lock version, echoed back on reassign.
 */
public record OrgChartNodeDto(
    UUID positionId,
    @BackendEnumAsString String positionType,
    UUID userId,
    String userName,
    String displayName,
    int sortIndex,
    Long version) {}

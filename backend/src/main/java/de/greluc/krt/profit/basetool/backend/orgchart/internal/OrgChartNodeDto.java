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

package de.greluc.krt.profit.basetool.backend.orgchart.internal;

import java.util.UUID;

/**
 * One filled person-node in the rendered org chart: the user who holds a single functional-rank
 * position. Carried inside the nested {@link OrgChartDto} tree (as the Bereichsleiter, a
 * Kommandoleiter, an Ensign, …). The {@code version} and {@code positionId} travel to the client so
 * the inline admin editor can reassign or remove the exact row without a re-fetch.
 *
 * @param positionId id of the underlying {@code org_chart_position} row; the handle every edit /
 *     remove / add-child action targets.
 * @param positionType the functional rank held in this node.
 * @param userId id of the user holding the position, or {@code null} when the holder is a free-text
 *     name (or the seat is vacant); preselects the user in the reassign picker when present.
 * @param userName the holding user's effective display name (display name, falling back to
 *     username), or {@code null} when the holder is a free-text name or the seat is vacant.
 * @param displayName the free-text holder name for a member without a Basetool account, or {@code
 *     null} when the holder is an account or the seat is vacant; mutually exclusive with {@code
 *     userId}.
 * @param sortIndex stable display order within the sibling group.
 * @param version optimistic-lock version of the row, echoed back on edit to detect concurrent
 *     changes.
 */
public record OrgChartNodeDto(
    UUID positionId,
    OrgChartPositionType positionType,
    UUID userId,
    String userName,
    String displayName,
    int sortIndex,
    Long version) {}

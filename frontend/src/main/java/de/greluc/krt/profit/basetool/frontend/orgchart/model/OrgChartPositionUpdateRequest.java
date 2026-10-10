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

import de.greluc.krt.profit.basetool.frontend.model.DtoMirror;
import java.util.UUID;

/**
 * The body of {@code PUT /api/v1/org-chart/positions/{id}}.
 *
 * @param userId the holding account, or {@code null}
 * @param name the seat name, or {@code null}
 * @param sortIndex the display order among its siblings, or {@code null}
 * @param version the optimistic-lock version the editor last saw
 * @param displayName the free-text holder, or {@code null}
 */
@DtoMirror
public record OrgChartPositionUpdateRequest(
    UUID userId, String name, Integer sortIndex, Long version, String displayName) {}

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

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Payload of {@code PUT /api/v1/org-chart/positions/{id}}, which changes a position's holder,
 * Kommando name or display order. A {@code null} field leaves the value unchanged; rank, scope and
 * parent are immutable.
 *
 * @param userId new account holder, or {@code null} to keep the current one; clears any {@link
 *     #displayName}
 * @param name new Kommando name, or {@code null} to keep it; blank clears it; only for {@code
 *     COMMAND_LEAD}
 * @param sortIndex new display order, or {@code null} to keep it
 * @param version the client's optimistic-lock version; required
 * @param displayName free-text holder to set, {@code null} to keep, blank to clear; rejected
 *     together with a {@code userId}
 */
public record OrgChartPositionUpdateRequest(
    UUID userId,
    @Size(max = 120) String name,
    Integer sortIndex,
    @NotNull Long version,
    @Size(max = 120) String displayName) {}

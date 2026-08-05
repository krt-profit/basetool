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

import java.util.List;

/**
 * The whole cross-order material-demand overview (REQ-ORDERS-034): the material still to be
 * gathered across every non-terminal ({@code OPEN} / {@code IN_PROGRESS}) job order the caller may
 * see, split by responsible org unit.
 *
 * <p>Wrapped in a record rather than returned as a bare {@code List} so the projection can grow
 * page-level context (a generation timestamp, a freshness marker) without breaking the response
 * shape — and so the JSON body is an object, per the API conventions.
 *
 * @param groups one group per responsible org unit that has outstanding demand, ordered by the
 *     unit's shorthand; empty when the caller may see no non-terminal order, which the page renders
 *     as its empty state rather than as an error
 */
public record MaterialDemandOverviewDto(List<MaterialDemandGroupDto> groups) {}

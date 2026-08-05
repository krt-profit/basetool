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

package de.greluc.krt.profit.basetool.frontend.model.dto;

import java.util.List;

/**
 * Frontend mirror of the backend {@code MaterialDemandOverviewDto}: the whole cross-order material
 * demand (REQ-ORDERS-034), split by responsible org unit.
 *
 * @param groups one group per responsible org unit with outstanding demand; empty when the caller
 *     may see no non-terminal order, which the page renders as its empty state
 * @param orderCount how many non-terminal orders the aggregation considered, shown so the user can
 *     see the basis of the numbers; includes orders that contributed no visible row
 */
public record MaterialDemandOverviewDto(List<MaterialDemandGroupDto> groups, int orderCount) {}

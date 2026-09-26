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

/**
 * One row of the org-unit blueprint availability overview: a blueprint variant family and how many
 * in-scope members own any of it. Carries no owner identity.
 *
 * @param productKey the variant family key (the aggregation and drill-down key)
 * @param productName the family's display label (the case-preserving base name)
 * @param ownerCount the number of distinct in-scope members that own the base or any variant
 */
public record BlueprintOverviewEntryDto(String productKey, String productName, long ownerCount) {}

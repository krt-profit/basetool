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

import java.util.UUID;

/**
 * Craftability overlay for one recipe requirement group, in the same order as {@link
 * PersonalBlueprintRecipeResponse#requirementGroups()}, carrying the effective quality the slot's
 * modifier slider defaults to.
 *
 * @param materialId the slot's limiting RESOURCE commodity id, or {@code null} when none is
 *     resolved
 * @param effectiveQuality effective quality from inventory alone, or {@code null} when no
 *     qualifying stock exists
 * @param effectiveQualityWithRefinery effective quality including the open refinery yield, or
 *     {@code null}
 */
public record CraftabilityGroupDto(
    UUID materialId, Double effectiveQuality, Double effectiveQualityWithRefinery) {}

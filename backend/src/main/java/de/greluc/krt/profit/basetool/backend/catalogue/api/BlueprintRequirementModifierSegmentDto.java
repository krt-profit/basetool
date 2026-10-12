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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

/**
 * Boundary DTO for one segment of a stepped / piecewise-linear modifier curve. Surfaces the
 * persisted {@code blueprint_modifier_segment} so the admin blueprint slider can compute the real
 * stat value at any quality: within {@link #qualityMin}..{@link #qualityMax} the value interpolates
 * linearly from {@link #modifierAtStart} to {@link #modifierAtEnd}.
 *
 * @param qualityMin the segment's start ingredient quality
 * @param qualityMax the segment's end ingredient quality
 * @param modifierAtStart the stat multiplier at {@link #qualityMin}
 * @param modifierAtEnd the stat multiplier at {@link #qualityMax}
 */
public record BlueprintRequirementModifierSegmentDto(
    Double qualityMin, Double qualityMax, Double modifierAtStart, Double modifierAtEnd) {}

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
 * Boundary DTO for one stat contribution a requirement group makes to the crafted item: the
 * affected stat and the multiplier band swept across the quality range.
 *
 * <p>When {@link #segments} is non-empty the stat changes stepwise and the raw {@link
 * #qualityMin}..{@link #qualityMax} pair holds only the first segment's bounds; slider extents must
 * use {@link #effectiveQualityMin}..{@link #effectiveQualityMax}, which span all segments.
 *
 * @param propertyKey internal stat key (e.g. {@code "weapon_damage"})
 * @param label human-readable stat name (e.g. {@code "Impact Force"})
 * @param betterWhen whether a higher / lower / neutral value is desirable
 * @param qualityMin lowest quality of the raw endpoint band (first segment only when stepped)
 * @param qualityMax highest quality of the raw endpoint band (first segment only when stepped)
 * @param modifierAtMinQuality stat multiplier at the minimum quality
 * @param modifierAtMaxQuality stat multiplier at the maximum quality
 * @param valueRangeType interpolation type between the endpoints (e.g. {@code "linear"})
 * @param segments per-segment ranges of a stepped modifier; empty for the linear form
 * @param effectiveQualityMin lowest quality the modifier covers: the smallest segment bound when
 *     stepped, else {@link #qualityMin}
 * @param effectiveQualityMax highest quality the modifier covers: the largest segment bound when
 *     stepped, else {@link #qualityMax}
 */
public record BlueprintRequirementModifierDto(
    String propertyKey,
    String label,
    String betterWhen,
    Double qualityMin,
    Double qualityMax,
    Double modifierAtMinQuality,
    Double modifierAtMaxQuality,
    String valueRangeType,
    List<BlueprintRequirementModifierSegmentDto> segments,
    Double effectiveQualityMin,
    Double effectiveQualityMax) {}

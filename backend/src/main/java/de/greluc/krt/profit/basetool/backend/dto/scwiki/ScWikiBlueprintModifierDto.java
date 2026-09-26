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

package de.greluc.krt.profit.basetool.backend.dto.scwiki;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/**
 * One stat contribution a requirement group makes to the crafted item (SC Wiki {@code
 * blueprint_modifier}): the stat moves through {@link #modifierRange} as ingredient quality sweeps
 * {@link #qualityRange}.
 *
 * <p>When {@link #valueSegments} is non-empty it defines a piecewise-linear curve and takes
 * precedence over the two ranges.
 *
 * @param propertyKey internal stat key (e.g. {@code "weapon_damage"}), or {@code null}
 * @param propertyUuid UUID of the property definition, or {@code null}
 * @param label human-readable stat name (e.g. {@code "Impact Force"}), or {@code null}
 * @param betterWhen whether a higher or lower value is desirable ({@code higher}/{@code
 *     lower}/{@code neutral}), or {@code null}
 * @param qualityRange the ingredient-quality band the modifier interpolates across, or {@code null}
 * @param modifierRange the stat-multiplier endpoints for that band, or {@code null}
 * @param valueRangeType interpolation type, e.g. {@code "linear"}, or {@code null}
 * @param valueSegments the per-segment ranges of a piecewise-linear modifier, or {@code null} /
 *     empty for the simple linear form
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiBlueprintModifierDto(
    @JsonProperty("property_key") String propertyKey,
    @JsonProperty("property_uuid") UUID propertyUuid,
    @JsonProperty("label") String label,
    @JsonProperty("better_when") String betterWhen,
    @JsonProperty("quality_range") ScWikiBlueprintQualityRangeDto qualityRange,
    @JsonProperty("modifier_range") ScWikiBlueprintModifierRangeDto modifierRange,
    @JsonProperty("value_range_type") String valueRangeType,
    @JsonProperty("value_segments") List<ScWikiBlueprintModifierSegmentDto> valueSegments) {}

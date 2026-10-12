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

package de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;

/**
 * One aggregated stat a blueprint affects across all its requirement groups (SC Wiki {@code
 * blueprint_summary_property}). A compact, de-duplicated roll-up of the {@link
 * ScWikiBlueprintModifierDto} property keys, used to badge a blueprint with the stats it influences
 * without expanding every group. Present only on the blueprint detail response.
 *
 * @param propertyKey internal stat key (e.g. {@code "weapon_damage"}), or {@code null}
 * @param propertyUuid UUID of the property definition, or {@code null}
 * @param label human-readable stat name (e.g. {@code "Impact Force"}), or {@code null}
 * @param betterWhen whether a higher or lower value is desirable, or {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiBlueprintSummaryPropertyDto(
    @JsonProperty("property_key") String propertyKey,
    @JsonProperty("property_uuid") UUID propertyUuid,
    @JsonProperty("label") String label,
    @JsonProperty("better_when") String betterWhen) {}

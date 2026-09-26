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

/**
 * One named build slot of a blueprint (SC Wiki {@code blueprint_requirement_group}), e.g. {@code
 * FRAME}, bundling its ingredients ({@link #children}) with its stat contributions ({@link
 * #modifiers}).
 *
 * <p>Present only on the blueprint detail response ({@code GET /api/blueprints/{uuid}}).
 *
 * @param key internal group key (e.g. {@code "EMITTER"}), or {@code null}
 * @param name display name of the slot (e.g. {@code "Emitter"}), or {@code null}
 * @param kind always {@code "group"} for a requirement group, or {@code null}
 * @param requiredCount number of children that must be fulfilled within the group, or {@code null}
 * @param modifiers the stat contributions of this slot, or {@code null} when the slot is inert
 * @param children the ingredient(s) that fill this slot, or {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiBlueprintRequirementGroupDto(
    @JsonProperty("key") String key,
    @JsonProperty("name") String name,
    @JsonProperty("kind") String kind,
    @JsonProperty("required_count") Integer requiredCount,
    @JsonProperty("modifiers") List<ScWikiBlueprintModifierDto> modifiers,
    @JsonProperty("children") List<ScWikiBlueprintRequirementChildDto> children) {}

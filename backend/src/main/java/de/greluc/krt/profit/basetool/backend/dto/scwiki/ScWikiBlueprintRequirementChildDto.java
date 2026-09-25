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
import java.util.UUID;

/**
 * One ingredient slot inside a {@link ScWikiBlueprintRequirementGroupDto} (SC Wiki {@code
 * blueprint_requirement_child}) with its {@link #minQuality} gate; {@link #kind} selects the
 * reference type.
 *
 * <ul>
 *   <li>{@code "resource"} → {@link #uuid} is a resource-type UUID (a {@code material}), {@link
 *       #quantityScu} the SCU amount;
 *   <li>{@code "item"} → {@link #uuid} is a game-item UUID (a {@code game_item}), {@link #quantity}
 *       the unit count.
 * </ul>
 *
 * @param key internal key of the child, or {@code null}
 * @param kind {@code "resource"} or {@code "item"} (case-insensitive), or {@code null}
 * @param uuid resource-type UUID (resource) or item UUID (item), or {@code null}
 * @param name display name of the required resource / item, or {@code null}
 * @param quantity discrete unit count required for an ITEM child, or {@code null}
 * @param quantityScu SCU amount required for a RESOURCE child, or {@code null}
 * @param minQuality minimum quality tier required, or {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiBlueprintRequirementChildDto(
    @JsonProperty("key") String key,
    @JsonProperty("kind") String kind,
    @JsonProperty("uuid") UUID uuid,
    @JsonProperty("name") String name,
    @JsonProperty("quantity") Integer quantity,
    @JsonProperty("quantity_scu") Double quantityScu,
    @JsonProperty("min_quality") Integer minQuality) {}

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
 * One ingredient line of a {@link ScWikiBlueprintDto}; {@code kind} selects the populated fields.
 *
 * <ul>
 *   <li>{@code "resource"} → {@link #resourceTypeUuid} + {@link #quantityScu}
 *   <li>{@code "item"} → {@link #itemUuid} + {@link #quantity}
 * </ul>
 *
 * <p>{@link #resourceTypeUuid} is the stable key of a resource line, trusted over any embedded
 * {@code link.uuid}.
 *
 * @param name display name of the ingredient
 * @param kind {@code "resource"} or {@code "item"} (case-insensitive)
 * @param resourceTypeUuid commodity UUID for a RESOURCE line, else {@code null}
 * @param itemUuid game-item UUID for an ITEM line, else {@code null}
 * @param quantityScu quantity in SCU for a RESOURCE line, else {@code null}
 * @param quantity quantity in whole units for an ITEM line, else {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiBlueprintIngredientDto(
    @JsonProperty("name") String name,
    @JsonProperty("kind") String kind,
    @JsonProperty("resource_type_uuid") UUID resourceTypeUuid,
    @JsonProperty("item_uuid") UUID itemUuid,
    @JsonProperty("quantity_scu") Double quantityScu,
    @JsonProperty("quantity") Integer quantity) {}

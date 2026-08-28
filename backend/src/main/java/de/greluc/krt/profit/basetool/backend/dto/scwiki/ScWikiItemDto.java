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
import java.util.Map;
import java.util.UUID;

/**
 * SC Wiki item DTO — the payload of {@code GET /api/items/{uuid}} (SC_WIKI_SYNC_PLAN.md §3.3),
 * consumed by the R4 closure-mode {@code ScWikiItemSyncService} to fill the Wiki-owned columns on
 * an existing {@code game_item} row.
 *
 * <p>{@link #size} is bound as a {@link String} because the Wiki emits it inconsistently (numeric
 * for components / weapons, sometimes absent or non-numeric for armor); the sync parses it
 * defensively into the integer {@code size_class}. {@link #description} is a locale → text map; the
 * sync reads {@code en_EN} and {@code de_DE}.
 *
 * @param uuid in-game asset UUID (the join key against {@code game_item.external_uuid})
 * @param slug Wiki URL slug
 * @param name display name
 * @param className RSI engine class name
 * @param classification classification path, e.g. {@code "FPS.Armor.Helmet"}
 * @param classificationLabel localised classification label
 * @param type Wiki type field
 * @param typeLabel Wiki type display label
 * @param subType Wiki sub-type field
 * @param subTypeLabel Wiki sub-type display label
 * @param size raw size token (parsed to an integer size class by the sync)
 * @param grade item grade
 * @param rarity item rarity
 * @param mass mass in kg
 * @param dimension bounding-box dimensions
 * @param manufacturer manufacturer reference (captured, not written in R4 — §6.3.5)
 * @param description locale → description text map ({@code en_EN}, {@code de_DE}, …)
 * @param isBaseVariant whether this is the base variant of a family
 * @param isCraftable whether the item is craftable
 * @param version game version the row was last seen in
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiItemDto(
    @JsonProperty("uuid") UUID uuid,
    @JsonProperty("slug") String slug,
    @JsonProperty("name") String name,
    @JsonProperty("class_name") String className,
    @JsonProperty("classification") String classification,
    @JsonProperty("classification_label") String classificationLabel,
    @JsonProperty("type") String type,
    @JsonProperty("type_label") String typeLabel,
    @JsonProperty("sub_type") String subType,
    @JsonProperty("sub_type_label") String subTypeLabel,
    @JsonProperty("size") String size,
    @JsonProperty("grade") String grade,
    @JsonProperty("rarity") String rarity,
    @JsonProperty("mass") Double mass,
    @JsonProperty("dimension") ScWikiDimensionDto dimension,
    @JsonProperty("manufacturer") ScWikiItemManufacturerDto manufacturer,
    @JsonProperty("description") Map<String, String> description,
    @JsonProperty("is_base_variant") Boolean isBaseVariant,
    @JsonProperty("is_craftable") Boolean isCraftable,
    @JsonProperty("version") String version)
    implements ScWikiRow {}

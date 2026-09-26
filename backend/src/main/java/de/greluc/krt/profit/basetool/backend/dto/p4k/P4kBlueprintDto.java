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

package de.greluc.krt.profit.basetool.backend.dto.p4k;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * One crafting-blueprint record from the P4K catalog, matched to {@code blueprint} by {@link #guid}
 * ({@code scwiki_uuid}) first, then {@link #key} ({@code scwiki_key}).
 *
 * <p>The importer only fills null blueprint columns; it never creates blueprints or rewrites
 * ingredient rows. Any field may be {@code null} when the source did not resolve it.
 *
 * @param guid DataForge {@code __ref} GUID (string form of {@code blueprint.scwiki_uuid})
 * @param key blueprint key {@code BP_CRAFT_*}; matches {@code blueprint.scwiki_key}
 * @param path source DCB record path (forensic; not persisted)
 * @param categoryGuid blueprint category GUID (forensic; not persisted)
 * @param producedItemGuid produced item GUID, resolved against {@code game_item.external_uuid} and
 *     filled into {@code blueprint.output_item} when null
 * @param craftTimeSeconds craft time in seconds, filled into {@code blueprint.craft_time_seconds}
 *     when null
 * @param ingredients ingredient lines; not read by the importer
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kBlueprintDto(
    String guid,
    String key,
    String path,
    String categoryGuid,
    String producedItemGuid,
    Integer craftTimeSeconds,
    List<P4kIngredientDto> ingredients) {}

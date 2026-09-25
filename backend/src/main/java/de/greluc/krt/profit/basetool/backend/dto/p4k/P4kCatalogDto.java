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
 * Root of the P4K catalog JSON that the external "KRT P4K Reader" builds from the game's {@code
 * Data/Game2.dcb}; {@code P4kImportService} uses it to enrich existing rows by GUID, never to seed
 * new ones.
 *
 * <p>Any array may be {@code null} or absent and is then treated as empty.
 *
 * @param manufacturers manufacturer records (joined on {@code scwiki_uuid} / {@code abbreviation})
 * @param items item records (joined on {@code game_item.external_uuid} / {@code class_name})
 * @param ships ship records (joined on {@code ship_type.external_uuid} / {@code class_name})
 * @param commodities commodity records (joined on {@code material.scwiki_uuid} / {@code name})
 * @param blueprints crafting-blueprint records (joined on {@code blueprint.scwiki_uuid} / {@code
 *     scwiki_key})
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record P4kCatalogDto(
    List<P4kManufacturerDto> manufacturers,
    List<P4kItemDto> items,
    List<P4kShipDto> ships,
    List<P4kCommodityDto> commodities,
    List<P4kBlueprintDto> blueprints) {}

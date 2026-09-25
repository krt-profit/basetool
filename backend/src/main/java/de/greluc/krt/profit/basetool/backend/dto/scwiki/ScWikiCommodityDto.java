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
 * SC Wiki commodity row from {@code GET /api/commodities}; unmapped fields are ignored.
 *
 * <p>{@code uuid} is the join key across Wiki and UEX when both carry one.
 *
 * @param uuid SC Wiki commodity UUID (matches the UEX commodity UUID when both exist)
 * @param key Wiki internal key (e.g. {@code "Agricium"})
 * @param name display name; subject to the junk filter of {@code ScWikiCommoditySyncService}
 * @param slug URL slug (lowercased, dash-separated form of {@code key})
 * @param kind taxonomy label, often empty and unreliable for filtering
 * @param densityGramPerCc physical density in g/cc (Wiki only)
 * @param isMineable whether the Wiki flags the commodity as mineable
 * @param hasHarvestables whether the Wiki flags the commodity as a harvestable source
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiCommodityDto(
    UUID uuid,
    String key,
    String name,
    String slug,
    String kind,
    @JsonProperty("density_g_per_cc") Double densityGramPerCc,
    @JsonProperty("is_mineable") Boolean isMineable,
    @JsonProperty("has_harvestables") Boolean hasHarvestables)
    implements ScWikiRow {}

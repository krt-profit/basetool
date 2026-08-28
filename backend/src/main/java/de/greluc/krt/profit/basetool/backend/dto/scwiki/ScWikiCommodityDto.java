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
 * SC Wiki commodity DTO — a single row from {@code GET /api/commodities}. R1 ships only the subset
 * of fields the R3 {@code ScWikiCommoditySyncService} will consume; the rest of the Wiki payload is
 * tolerated via {@link JsonIgnoreProperties}. R3 expands the record with {@code raw_versions} /
 * {@code refined_versions} / {@code uex_prices} cross-references.
 *
 * <p>Field provenance (verified 2026-05-27 against a live response):
 *
 * <pre>{@code
 * {
 *   "uuid":              "dc6fbcbb-...",
 *   "key":               "Agricium",
 *   "name":              "Agricium",
 *   "slug":              "agricium",
 *   "kind":              "",
 *   "density_g_per_cc":  1,
 *   "is_mineable":       false,
 *   "has_harvestables":  false
 * }
 * }</pre>
 *
 * <p>{@code uuid} is the canonical join key across Wiki and UEX when both systems carry one. The
 * §4.3 "junk filter" runs on {@code name} (HTML, underscores, hardcoded atmosphere terms); see
 * {@code ScWikiCommoditySyncService} (R3) for the filter implementation.
 *
 * @param uuid SC Wiki commodity UUID (matches UEX commodity UUID when both exist)
 * @param key Wiki internal key (e.g. {@code "Agricium"})
 * @param name display name; subject to the §4.3 hard-junk filter
 * @param slug URL slug (lowercased + dash-separated form of {@code key})
 * @param kind taxonomy label (often empty; the §4.3 verification shows it is unreliable for
 *     filtering)
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

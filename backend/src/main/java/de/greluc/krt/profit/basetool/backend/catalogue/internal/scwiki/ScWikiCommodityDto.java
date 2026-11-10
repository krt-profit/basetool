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
import de.greluc.krt.profit.basetool.backend.catalogue.api.CommodityMiningData;
import java.util.List;
import java.util.UUID;
import lombok.Builder;

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
 * @param tier rarity label, stored as tier 1 ({@code common}) to 5 ({@code legendary})
 */
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiCommodityDto(
    UUID uuid,
    String key,
    String name,
    String slug,
    String kind,
    @JsonProperty("density_g_per_cc") Double densityGramPerCc,
    @JsonProperty("is_mineable") Boolean isMineable,
    @JsonProperty("has_harvestables") Boolean hasHarvestables,
    ScWikiCommodityRarity tier,
    Double instability,
    Double resistance,
    Double volatility,
    @JsonProperty("volatility_health_decay_per_second") Double volatilityHealthDecayPerSecond,
    Double signature,
    @JsonProperty("has_ship_mineables") Boolean hasShipMineables,
    @JsonProperty("has_ground_vehicle_mineables") Boolean hasGroundVehicleMineables,
    @JsonProperty("has_fps_mineables") Boolean hasFpsMineables,
    @JsonProperty("has_salvage") Boolean hasSalvage,
    @JsonProperty("box_sizes_scu") List<Double> boxSizesScu,
    @JsonProperty("commodity_groups") List<String> commodityGroups,
    List<String> methods,
    List<String> systems,
    List<CommodityMiningData.Location> locations)
    implements ScWikiRow {

  /**
   * Projects typed source evidence without changing market or canonical resource identity.
   *
   * @return immutable mining and cargo metadata
   */
  public CommodityMiningData miningData() {
    return new CommodityMiningData(
        tier == null ? null : tier.getTier(),
        instability,
        resistance,
        volatility,
        volatilityHealthDecayPerSecond,
        signature,
        isMineable,
        hasShipMineables,
        hasGroundVehicleMineables,
        hasFpsMineables,
        hasHarvestables,
        hasSalvage,
        boxSizesScu,
        commodityGroups,
        methods,
        systems,
        locations);
  }
}

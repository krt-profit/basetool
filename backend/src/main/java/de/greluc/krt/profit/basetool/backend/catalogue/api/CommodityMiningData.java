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

package de.greluc.krt.profit.basetool.backend.catalogue.api;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import org.jetbrains.annotations.Nullable;

/** Wiki-owned mining characteristics, cargo sizes and observed resource locations. */
@Builder
public record CommodityMiningData(
    @Nullable Integer tier,
    @Nullable Double instability,
    @Nullable Double resistance,
    @Nullable Double volatility,
    @Nullable Double volatilityHealthDecayPerSecond,
    @Nullable Double signature,
    @Nullable Boolean mineable,
    @Nullable Boolean shipMineable,
    @Nullable Boolean groundVehicleMineable,
    @Nullable Boolean fpsMineable,
    @Nullable Boolean harvestable,
    @Nullable Boolean salvage,
    List<Double> boxSizesScu,
    List<String> commodityGroups,
    List<String> methods,
    List<String> systems,
    List<Location> locations) {

  /** Copies imported collections so cached catalogue data cannot be mutated by a caller. */
  public CommodityMiningData {
    boxSizesScu = boxSizesScu == null ? List.of() : List.copyOf(boxSizesScu);
    commodityGroups = commodityGroups == null ? List.of() : List.copyOf(commodityGroups);
    methods = methods == null ? List.of() : List.copyOf(methods);
    systems = systems == null ? List.of() : List.copyOf(systems);
    locations = locations == null ? List.of() : List.copyOf(locations);
  }

  /** A source location reference; its UUID belongs to the Wiki location catalogue. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Location(
      @Nullable UUID uuid,
      @Nullable String name,
      @Nullable @JsonAlias("display_name") String displayName,
      @Nullable String system,
      @Nullable String type,
      @Nullable @JsonAlias("parent_name") String parentName,
      @Nullable @JsonAlias("parent_type") String parentType,
      List<Occurrence> entries) {

    /** Makes a source location's occurrence evidence immutable. */
    public Location {
      entries = entries == null ? List.of() : List.copyOf(entries);
    }
  }

  /** Resource-group and quality evidence for one source location. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Occurrence(
      @Nullable @JsonAlias("group_name") String groupName,
      @Nullable @JsonAlias("resource_kind") String resourceKind,
      @Nullable @JsonAlias("quality_min") Integer qualityMin,
      @Nullable @JsonAlias("quality_max") Integer qualityMax,
      @Nullable @JsonAlias("entry_count") Integer entryCount) {}
}

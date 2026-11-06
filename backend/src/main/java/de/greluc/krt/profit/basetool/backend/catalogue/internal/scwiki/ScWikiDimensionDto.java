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
import org.jetbrains.annotations.Nullable;

/**
 * Separates physical dimensions, cargo occupancy and inventory display dimensions.
 *
 * @param width physical width in metres
 * @param height physical height in metres
 * @param length physical length in metres
 * @param physical explicit physical bounding box
 * @param cargo cargo-grid occupancy in metres
 * @param ui inventory display dimensions in metres
 * @param volumeScu stored occupancy volume in SCU
 * @param legacyPhysical deprecated explicit physical bounding box
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ScWikiDimensionDto(
    @Nullable @JsonProperty("width") Double width,
    @Nullable @JsonProperty("height") Double height,
    @Nullable @JsonProperty("length") Double length,
    @Nullable @JsonProperty("dimensions") Axes physical,
    @Nullable @JsonProperty("cargo_dimension") Axes cargo,
    @Nullable @JsonProperty("ui_dimension") Axes ui,
    @Nullable @JsonProperty("volume") Double volumeScu,
    @Nullable @JsonProperty("true_dimension") Axes legacyPhysical) {

  /** Selects physical axes without substituting modern UI or cargo occupancy dimensions. */
  public ScWikiDimensionDto {
    physical = physical == null ? legacyPhysical : physical;
    if (physical != null) {
      width = physical.width();
      height = physical.height();
      length = physical.length();
    } else if (cargo != null || ui != null) {
      width = null;
      height = null;
      length = null;
    }
  }

  /**
   * Represents an older payload containing only its physical axes.
   *
   * @param width width in metres
   * @param height height in metres
   * @param length length in metres
   */
  public ScWikiDimensionDto(
      @Nullable Double width, @Nullable Double height, @Nullable Double length) {
    this(width, height, length, null, null, null, null, null);
  }

  /**
   * Contains one explicitly named set of dimensions in metres.
   *
   * @param width width in metres
   * @param height height in metres
   * @param length length in metres
   */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Axes(@Nullable Double width, @Nullable Double height, @Nullable Double length) {}
}

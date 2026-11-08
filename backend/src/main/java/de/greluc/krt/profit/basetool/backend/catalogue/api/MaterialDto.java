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

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Data transfer record carrying Material payload; {@code resourceGroupManual} and {@code
 * refinedMaterialManual} tell whether a P4K APPLY keeps the admin's group and refined material
 * (REQ-P4K-024, REQ-P4K-025).
 */
public record MaterialDto(
    UUID id,
    String name,
    String type,
    String quantityType,
    String description,
    MaterialDto refinedMaterial,
    MaterialCategoryDto category,
    @JsonProperty("isIllegal") Boolean isIllegal,
    @JsonProperty("isVolatileQt") Boolean isVolatileQt,
    @JsonProperty("isVolatileTime") Boolean isVolatileTime,
    @JsonProperty("isManualRawMaterial") Boolean isManualRawMaterial,
    @JsonProperty("isJobOrder") Boolean isJobOrder,
    @JsonProperty("isManualEntry") Boolean isManualEntry,
    @JsonProperty("isVisible") Boolean isVisible,
    Long version,
    @JsonProperty(access = JsonProperty.Access.READ_ONLY) @Nullable CommodityMiningData miningData,
    @JsonProperty(access = JsonProperty.Access.READ_ONLY) @Nullable String nameDe,
    @JsonProperty(access = JsonProperty.Access.READ_ONLY) @Nullable Boolean resourceGroupManual,
    @JsonProperty(access = JsonProperty.Access.READ_ONLY) @Nullable Boolean refinedMaterialManual) {

  /**
   * Creates a material without a German name or override flags, which the P4K and the admin
   * overrides write only.
   */
  public MaterialDto(
      UUID id,
      String name,
      String type,
      String quantityType,
      String description,
      MaterialDto refinedMaterial,
      MaterialCategoryDto category,
      Boolean isIllegal,
      Boolean isVolatileQt,
      Boolean isVolatileTime,
      Boolean isManualRawMaterial,
      Boolean isJobOrder,
      Boolean isManualEntry,
      Boolean isVisible,
      Long version,
      @Nullable CommodityMiningData miningData) {
    this(
        id,
        name,
        type,
        quantityType,
        description,
        refinedMaterial,
        category,
        isIllegal,
        isVolatileQt,
        isVolatileTime,
        isManualRawMaterial,
        isJobOrder,
        isManualEntry,
        isVisible,
        version,
        miningData,
        null,
        null,
        null);
  }

  /** Creates the editable summary without source-owned mining enrichment. */
  public MaterialDto(
      UUID id,
      String name,
      String type,
      String quantityType,
      String description,
      MaterialDto refinedMaterial,
      MaterialCategoryDto category,
      Boolean isIllegal,
      Boolean isVolatileQt,
      Boolean isVolatileTime,
      Boolean isManualRawMaterial,
      Boolean isJobOrder,
      Boolean isManualEntry,
      Boolean isVisible,
      Long version) {
    this(
        id,
        name,
        type,
        quantityType,
        description,
        refinedMaterial,
        category,
        isIllegal,
        isVolatileQt,
        isVolatileTime,
        isManualRawMaterial,
        isJobOrder,
        isManualEntry,
        isVisible,
        version,
        null);
  }
}

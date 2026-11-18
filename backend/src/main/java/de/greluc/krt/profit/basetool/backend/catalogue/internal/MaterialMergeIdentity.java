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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import de.greluc.krt.profit.basetool.backend.catalogue.api.CommodityMiningData;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialCategory;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSourceSystem;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.util.StringUtils;

/**
 * What a merged material hands to the material that stays (REQ-DATA-046), read before the merged
 * row is deleted so the survivor can take its unique SC Wiki UUID. The survivor keeps its own name,
 * UEX listing and every value it holds; it takes the SC Wiki identity when it has none, and the
 * Wiki, P4K and admin values it lacks.
 *
 * @param id the merged material's id
 * @param scwikiUuid the SC Wiki UUID
 * @param scwikiKey the SC Wiki key
 * @param scwikiSlug the SC Wiki slug
 * @param scwikiSyncedAt when the SC Wiki last wrote the row
 * @param scwikiDeletedAt when the SC Wiki stopped listing the row
 * @param miningData the SC Wiki mining data
 * @param density the density in g/cm³
 * @param instability the mining instability
 * @param resistance the mining resistance
 * @param nameDe the German name
 * @param description the description
 * @param category the resource group
 * @param refinedMaterial the material it refines into
 * @param jobOrder whether job orders may request it
 * @param manualRawMaterial whether an admin marked it a raw material
 */
record MaterialMergeIdentity(
    @NotNull UUID id,
    @Nullable UUID scwikiUuid,
    @Nullable String scwikiKey,
    @Nullable String scwikiSlug,
    @Nullable Instant scwikiSyncedAt,
    @Nullable Instant scwikiDeletedAt,
    @Nullable CommodityMiningData miningData,
    @Nullable Double density,
    @Nullable Double instability,
    @Nullable Double resistance,
    @Nullable String nameDe,
    @Nullable String description,
    @Nullable MaterialCategory category,
    @Nullable Material refinedMaterial,
    boolean jobOrder,
    boolean manualRawMaterial) {

  /**
   * Reads what a merged material hands over.
   *
   * @param row the merged material
   * @return its identity and values
   */
  @NotNull
  static MaterialMergeIdentity of(@NotNull Material row) {
    return new MaterialMergeIdentity(
        Objects.requireNonNull(row.getId()),
        row.getScwikiUuid(),
        row.getScwikiKey(),
        row.getScwikiSlug(),
        row.getScwikiSyncedAt(),
        row.getScwikiDeletedAt(),
        row.getMiningData(),
        row.getDensityGramPerCc(),
        row.getInstability(),
        row.getResistance(),
        row.getNameDe(),
        row.getDescription(),
        row.getCategory(),
        row.getRefinedMaterial(),
        Boolean.TRUE.equals(row.getIsJobOrder()),
        Boolean.TRUE.equals(row.getIsManualRawMaterial()));
  }

  /**
   * Hands the identity to the material that stays; a value the survivor holds stays.
   *
   * @param survivor the material that stays
   */
  void giveTo(@NotNull Material survivor) {
    if (scwikiUuid != null && survivor.getScwikiUuid() == null) {
      survivor.setScwikiUuid(scwikiUuid);
      write(scwikiKey, survivor::setScwikiKey);
      write(scwikiSlug, survivor::setScwikiSlug);
      write(scwikiSyncedAt, survivor::setScwikiSyncedAt);
      survivor.setScwikiDeletedAt(scwikiDeletedAt);
      if (survivor.getSourceSystems() == MaterialSourceSystem.UEX_ONLY) {
        survivor.setSourceSystems(MaterialSourceSystem.BOTH);
      }
    }
    fill(survivor::getMiningData, miningData, survivor::setMiningData);
    fill(survivor::getDensityGramPerCc, density, survivor::setDensityGramPerCc);
    fill(survivor::getInstability, instability, survivor::setInstability);
    fill(survivor::getResistance, resistance, survivor::setResistance);
    fill(survivor::getCategory, category, survivor::setCategory);
    if (!StringUtils.hasText(survivor.getNameDe())) {
      fill(survivor::getNameDe, nameDe, survivor::setNameDe);
    }
    if (!StringUtils.hasText(survivor.getDescription()) && StringUtils.hasText(description)) {
      survivor.setDescription(description);
    }
    takeRefinedMaterial(survivor);
    survivor.setIsJobOrder(Boolean.TRUE.equals(survivor.getIsJobOrder()) || jobOrder);
    survivor.setIsManualRawMaterial(
        Boolean.TRUE.equals(survivor.getIsManualRawMaterial()) || manualRawMaterial);
  }

  /**
   * Points the survivor's refined-from link past the merged row: a link to the merged row follows
   * the merged row's own link, and a survivor without a link takes it; a link would never point the
   * survivor at itself.
   *
   * @param survivor the material that stays
   */
  private void takeRefinedMaterial(@NotNull Material survivor) {
    Material inherited =
        refinedMaterial == null || survivor.getId().equals(refinedMaterial.getId())
            ? null
            : refinedMaterial;
    Material current = survivor.getRefinedMaterial();
    if (current == null || id.equals(current.getId())) {
      survivor.setRefinedMaterial(inherited);
    }
  }

  /**
   * Writes a value the survivor lacks.
   *
   * @param current reads the survivor's value
   * @param value the merged row's value, or {@code null}
   * @param setter the survivor's setter
   * @param <T> the value's type
   */
  private static <T> void fill(
      @NotNull Supplier<T> current, @Nullable T value, @NotNull Consumer<T> setter) {
    if (current.get() == null) {
      write(value, setter);
    }
  }

  /**
   * Writes a value the merged row carries.
   *
   * @param value the merged row's value, or {@code null} when it has none
   * @param setter the survivor's setter
   * @param <T> the value's type
   */
  private static <T> void write(@Nullable T value, @NotNull Consumer<T> setter) {
    if (value != null) {
      setter.accept(value);
    }
  }
}

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

package de.greluc.krt.profit.basetool.frontend.inventory.model;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.QualityTierDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import de.greluc.krt.profit.basetool.frontend.kernel.model.GameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.OrgUnitReferenceDto;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;

/**
 * Frontend mirror of the backend {@code StockLevelDto}: a minimum stock level with its figures
 * (REQ-INV-067, REQ-INV-068).
 *
 * @param id the level
 * @param version the level's version, echoed on a change or delete
 * @param orgUnit the unit whose pool the level watches
 * @param material the watched material, {@code null} for a game-item level
 * @param gameItem the watched game item, {@code null} for a material level
 * @param qualityTier the tier, {@code null} for any quality
 * @param minimum the amount below which the level is low
 * @param target the amount the level is filled up to
 * @param allocated the free stock allocated to the level
 * @param shortfall the target minus the allocated stock, never below zero
 * @param fillRatio the allocated stock divided by the minimum
 * @param low whether the allocated stock is below the minimum
 * @param requested the outstanding amount of open supply needs and requesting job orders at the
 *     level's tier or above; shown only
 * @param editable whether the caller may change and delete the level
 */
@DtoMirror
public record StockLevelDto(
    UUID id,
    Long version,
    OrgUnitReferenceDto orgUnit,
    @Nullable MaterialReferenceDto material,
    @Nullable GameItemReferenceDto gameItem,
    @Nullable QualityTierDto qualityTier,
    double minimum,
    double target,
    double allocated,
    double shortfall,
    double fillRatio,
    boolean low,
    double requested,
    boolean editable) {

  private static final String PIECE = "PIECE";

  /**
   * The name of the watched material or game item.
   *
   * @return the catalogue entry's display name
   */
  public String entryName() {
    return material != null ? material.name() : gameItem.name();
  }

  /**
   * The quantity type the amounts are shown in: game items are counted in whole pieces.
   *
   * @return the material's quantity type, {@code PIECE} for a game item
   */
  public String quantityType() {
    return material != null ? material.quantityType() : PIECE;
  }

  /**
   * The fill ratio as a whole percentage for the meter and the label.
   *
   * @return the rounded percentage, never below zero
   */
  public long fillPercent() {
    return Math.max(0, Math.round(fillRatio * 100));
  }

  /**
   * Whether the level's tier was deactivated („Stufe inaktiv").
   *
   * @return {@code true} iff the level has a tier and it is inactive
   */
  public boolean tierInactive() {
    return qualityTier != null && !qualityTier.active();
  }
}

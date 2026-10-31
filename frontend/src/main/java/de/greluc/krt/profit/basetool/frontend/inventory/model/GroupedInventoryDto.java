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

import de.greluc.krt.profit.basetool.frontend.kernel.model.DtoMirror;
import de.greluc.krt.profit.basetool.frontend.kernel.model.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.UserReferenceDto;
import java.util.List;
import java.util.Objects;

/**
 * Frontend mirror of the per-catalog-entry Lager roll-up, broken down into {@link
 * InventoryStackDto} stacks. A material group carries {@code material} and quality aggregates; a
 * game-item group carries {@code gameItem} with {@code null} quality aggregates (REQ-INV-029).
 *
 * @param material the grouping material, or {@code null} for a game-item group
 * @param gameItem the grouping game item, or {@code null} for a material group
 * @param totalAmount the summed quantity across all stacks
 * @param averageQuality amount-weighted mean quality, or {@code null}
 * @param maxQuality highest quality across the stacks, or {@code null}
 * @param stacks the per-stock-identity stacks of this group
 * @param lowStock whether a minimum stock level of this material is low (REQ-INV-068)
 */
@DtoMirror
public record GroupedInventoryDto(
    MaterialReferenceDto material,
    InventoryGameItemReferenceDto gameItem,
    Double totalAmount,
    Double averageQuality,
    Integer maxQuality,
    List<InventoryStackDto> stacks,
    boolean lowStock) {

  /**
   * Creates a group that no low level marks.
   *
   * @param material the grouping material, or {@code null} for a game-item group
   * @param gameItem the grouping game item, or {@code null} for a material group
   * @param totalAmount the summed quantity across all stacks
   * @param averageQuality amount-weighted mean quality, or {@code null}
   * @param maxQuality highest quality across the stacks, or {@code null}
   * @param stacks the per-stock-identity stacks of this group
   */
  public GroupedInventoryDto(
      MaterialReferenceDto material,
      InventoryGameItemReferenceDto gameItem,
      Double totalAmount,
      Double averageQuality,
      Integer maxQuality,
      List<InventoryStackDto> stacks) {
    this(material, gameItem, totalAmount, averageQuality, maxQuality, stacks, false);
  }

  /**
   * Counts the distinct owning users across this group's stacks, for the grouped Lager row's
   * context line ("{n} Nutzer / {m} Stacks") on the squadron-wide {@code /inventory/all} view.
   *
   * @return the number of distinct users owning at least one stack of this catalog entry
   */
  public int userCount() {
    return (int)
        stacks.stream()
            .map(InventoryStackDto::user)
            .filter(Objects::nonNull)
            .map(UserReferenceDto::id)
            .distinct()
            .count();
  }
}

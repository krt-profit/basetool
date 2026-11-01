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

package de.greluc.krt.profit.basetool.frontend.joborder.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Query parameters that prefill the first line and the requesting unit of the create-order form, as
 * the „Auftrag anlegen" shortcut of a low minimum stock level sends them (REQ-INV-068): a material
 * line, or an item line for a game-item level.
 *
 * @param materialId the material of the first line, or {@code null} for an empty form
 * @param minQuality the quality floor of the first line, {@code 0} for none
 * @param amount the amount of the first line
 * @param requestingOrgUnitId the requesting unit
 * @param gameItemId the item of the first item line, or {@code null}; ignored when a material is
 *     given
 * @param gameItemName the label the item picker shows for {@code gameItemId}
 */
public record JobOrderPrefill(
    @Nullable UUID materialId,
    @Nullable Integer minQuality,
    @Nullable Double amount,
    @Nullable UUID requestingOrgUnitId,
    @Nullable UUID gameItemId,
    @Nullable String gameItemName) {

  /**
   * Whether the form starts as an item order.
   *
   * @return {@code true} iff an item and no material is given
   */
  public boolean itemOrder() {
    return gameItemId != null && materialId == null;
  }

  /**
   * Writes the prefill into a fresh material-order form; absent parameters leave the form's
   * defaults.
   *
   * @param form the form to prefill
   */
  public void applyTo(@NotNull JobOrderForm form) {
    if (materialId != null) {
      JobOrderForm.JobOrderMaterialForm line = form.getMaterials().getFirst();
      line.setMaterialId(materialId);
      if (minQuality != null) {
        line.setMinQuality(minQuality);
      }
      line.setAmount(amount);
    }
    if (requestingOrgUnitId != null) {
      form.setRequestingOrgUnitId(requestingOrgUnitId);
    }
  }

  /**
   * Writes the requesting unit into a fresh item-order form.
   *
   * @param form the form to prefill
   */
  public void applyTo(@NotNull JobOrderItemForm form) {
    if (requestingOrgUnitId != null) {
      form.setRequestingOrgUnitId(requestingOrgUnitId);
    }
  }

  /**
   * Builds the first item line in the shape of the item editor's {@code EDIT_ITEMS} entries.
   *
   * @return the item, its label and the whole-unit amount, at least one
   * @throws IllegalStateException when this prefill is not an item order
   */
  public @NotNull Map<String, Object> itemLine() {
    if (!itemOrder()) {
      throw new IllegalStateException("The prefill carries no item");
    }
    Map<String, Object> line = new LinkedHashMap<>();
    line.put("gameItemId", Objects.requireNonNull(gameItemId).toString());
    line.put("gameItemName", gameItemName);
    line.put("amount", amount == null ? 1 : Math.max(1, (int) Math.ceil(amount)));
    return line;
  }
}

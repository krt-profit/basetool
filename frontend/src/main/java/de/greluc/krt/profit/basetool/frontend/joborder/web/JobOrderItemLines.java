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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.ItemDerivationDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.SubAssemblySuggestionDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.ItemDerivationView;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemMaterialDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.SubAssemblyOption;
import de.greluc.krt.profit.basetool.frontend.kernel.web.ItemPickerLabels;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Builds the item lines' data for the item-order form, with each item labelled like the item picker
 * (REQ-INV-064).
 */
@Component
@RequiredArgsConstructor
public class JobOrderItemLines {

  /** Labels a ship component with its size, grade and class. */
  private final ItemPickerLabels itemPickerLabels;

  /**
   * Builds the {@code window.EDIT_ITEMS} prefill: one entry per item line with its id, booked
   * production, item and its label, blueprint, amount, parent index and material-quality map.
   *
   * <p>The list index serves as the client line id.
   *
   * @param items the item lines of the order being edited
   * @return the JS-serializable prefill list
   */
  @NotNull
  public List<Map<String, Object>> editPrefill(@NotNull List<JobOrderItemDto> items) {
    Map<UUID, Integer> idToIndex = new HashMap<>();
    for (int i = 0; i < items.size(); i++) {
      idToIndex.put(items.get(i).id(), i);
    }
    List<Map<String, Object>> result = new ArrayList<>();
    for (JobOrderItemDto line : items) {
      Map<String, String> qualities = new LinkedHashMap<>();
      if (line.materials() != null) {
        for (JobOrderItemMaterialDto m : line.materials()) {
          if (m.material() != null && m.material().id() != null) {
            qualities.put(m.material().id().toString(), m.qualityRequirement());
          }
        }
      }
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("id", line.id() != null ? line.id().toString() : null);
      entry.put("manufactured", line.manufacturedAmount() != null ? line.manufacturedAmount() : 0);
      entry.put("gameItemId", line.gameItem() != null ? line.gameItem().id().toString() : null);
      entry.put(
          "gameItemName", line.gameItem() != null ? itemPickerLabels.label(line.gameItem()) : null);
      entry.put("blueprintId", line.blueprint() != null ? line.blueprint().id().toString() : null);
      entry.put("amount", line.amount() != null ? line.amount() : 1);
      entry.put(
          "parentId", line.parentItemId() != null ? idToIndex.get(line.parentItemId()) : null);
      entry.put("qualities", qualities);
      result.add(entry);
    }
    return result;
  }

  /**
   * Labels the sub-assemblies of a derivation preview.
   *
   * @param derivation the backend preview, {@code null} when it could not be read
   * @return the preview with labelled sub-assemblies, {@code null} for a missing preview
   */
  @Nullable
  @Contract("null -> null; !null -> !null")
  public ItemDerivationView derivation(@Nullable ItemDerivationDto derivation) {
    if (derivation == null) {
      return null;
    }
    List<SubAssemblySuggestionDto> subAssemblies =
        derivation.subAssemblies() != null ? derivation.subAssemblies() : List.of();
    return new ItemDerivationView(
        derivation.blueprint(),
        derivation.amount(),
        derivation.materials(),
        subAssemblies.stream().map(this::subAssembly).toList(),
        derivation.unresolvedIngredients());
  }

  private SubAssemblyOption subAssembly(SubAssemblySuggestionDto suggestion) {
    String label =
        suggestion.gameItem() != null ? itemPickerLabels.label(suggestion.gameItem()) : null;
    return new SubAssemblyOption(
        suggestion.gameItem(), label, suggestion.quantity(), suggestion.blueprints());
  }
}

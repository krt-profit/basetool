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

package de.greluc.krt.profit.basetool.backend.joborder.api;

import de.greluc.krt.profit.basetool.backend.catalogue.api.ItemOrderRecipeFacts;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierSelection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

/** The exact immutable ITEM requirements and their observed derivation inputs for one action. */
public record JobOrderItemPlan(@NotNull List<Line> lines, @NotNull Facts facts) {
  /** Copies the ordered derived lines without retaining managed catalogue references. */
  public JobOrderItemPlan {
    lines = List.copyOf(lines);
  }

  /**
   * Collects only the selected resolved materials, including zero-quantity snapshot lines.
   *
   * @return the exact material scopes this prepared action can introduce
   */
  @NotNull
  @Unmodifiable
  public Set<UUID> materialIds() {
    Set<UUID> result = new LinkedHashSet<>();
    lines.forEach(line -> line.materials().forEach(material -> result.add(material.materialId())));
    return Set.copyOf(result);
  }

  /** One ordered payload line and its resolved canonical item and material requirements. */
  public record Line(
      @NotNull CreateJobOrderItemLineDto payload,
      @NotNull UUID gameItemId,
      @NotNull List<MaterialLine> materials) {
    /** Deep-copies the payload's quality choices and the derived requirement list. */
    public Line {
      payload = copyPayload(payload);
      materials = List.copyOf(materials);
    }
  }

  /**
   * Copies a requested line while freezing its quality-selection list.
   *
   * @param payload the requested item-line values
   * @return an independent immutable selection snapshot
   */
  @NotNull
  public static CreateJobOrderItemLineDto copyPayload(@NotNull CreateJobOrderItemLineDto payload) {
    return new CreateJobOrderItemLineDto(
        payload.id(),
        payload.gameItemId(),
        payload.blueprintId(),
        payload.amount(),
        payload.materials() == null ? List.of() : List.copyOf(payload.materials()),
        payload.clientLineId(),
        payload.parentClientLineId());
  }

  /** A selected material quantity and quality identity, independent of managed entity state. */
  public record MaterialLine(
      @NotNull UUID materialId, double requiredQuantity, @NotNull UUID qualityTierId) {}

  /** A persisted item's current recipe, progress, parent identity and optimistic version. */
  public record KeptLine(
      @NotNull UUID id,
      @Nullable Long version,
      @Nullable Integer amount,
      @Nullable Integer manufacturedAmount,
      @Nullable Integer deliveredAmount,
      @NotNull UUID gameItemId,
      @NotNull UUID blueprintId,
      @Nullable UUID parentItemId) {}

  /** A kept line's current material snapshot and quality identity. */
  public record KeptMaterial(
      @NotNull UUID id,
      @NotNull UUID itemId,
      @NotNull UUID materialId,
      @NotNull UUID qualityTierId,
      @Nullable Double requiredQuantity,
      @Nullable Long version) {}

  /**
   * Fresh scalar observations that must still match before the prepared action mutates an order.
   */
  public record Facts(
      @NotNull Set<UUID> blueprintIds,
      @NotNull Set<UUID> requestedItemIds,
      @NotNull Set<UUID> keptItemIds,
      @NotNull List<ItemOrderRecipeFacts.Recipe> recipes,
      @NotNull List<ItemOrderRecipeFacts.Group> groups,
      @NotNull List<ItemOrderRecipeFacts.Ingredient> ingredients,
      @NotNull List<ItemOrderRecipeFacts.Output> outputs,
      @NotNull List<ItemOrderRecipeFacts.MaterialBridge> bridges,
      @NotNull List<ItemOrderRecipeFacts.OrderedItem> orderedItems,
      @NotNull QualityTierSelection quality,
      @NotNull List<KeptLine> keptLines,
      @NotNull List<KeptMaterial> keptMaterials) {
    /** Copies every observed collection so waiting cannot replace the plan's source facts. */
    public Facts {
      blueprintIds = Set.copyOf(blueprintIds);
      requestedItemIds = Set.copyOf(requestedItemIds);
      keptItemIds = Set.copyOf(keptItemIds);
      recipes = List.copyOf(recipes);
      groups = List.copyOf(groups);
      ingredients = List.copyOf(ingredients);
      outputs = List.copyOf(outputs);
      bridges = List.copyOf(bridges);
      orderedItems = List.copyOf(orderedItems);
      keptLines = List.copyOf(keptLines);
      keptMaterials = List.copyOf(keptMaterials);
    }
  }
}

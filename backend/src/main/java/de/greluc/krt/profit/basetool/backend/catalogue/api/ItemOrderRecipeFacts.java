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

import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Immutable database facts used to snapshot an explicitly selected item-order recipe. */
public final class ItemOrderRecipeFacts {
  private ItemOrderRecipeFacts() {}

  /** The selected recipe's identity, output, group policy and retirement state. */
  public record Recipe(
      @NotNull UUID id,
      @Nullable UUID outputItemId,
      @Nullable String outputName,
      @Nullable String scwikiKey,
      @Nullable Integer requiredGroupCount,
      boolean retired,
      @Nullable Long version) {}

  /** One recipe-ordered mandatory or optional requirement group. */
  public record Group(
      @NotNull UUID id,
      @NotNull UUID blueprintId,
      int orderIndex,
      boolean optional,
      @Nullable Long version) {}

  /** One recipe cost with the scalar facts its material resolution and unit scaling read. */
  public record Ingredient(
      @NotNull UUID id,
      @NotNull UUID blueprintId,
      int orderIndex,
      @NotNull BlueprintIngredientKind kind,
      @Nullable UUID groupId,
      @Nullable UUID materialId,
      @Nullable QuantityType quantityType,
      @Nullable UUID gameItemId,
      @Nullable String gameItemName,
      @Nullable String wikiName,
      @Nullable Double quantityScu,
      @Nullable Integer quantityUnits,
      @Nullable Integer minQuality,
      @Nullable Long version) {}

  /** One listed active recipe producing an ingredient's direct or retained output identity. */
  public record Output(
      @NotNull UUID ingredientId,
      @NotNull UUID blueprintId,
      @Nullable String outputName,
      @Nullable String scwikiKey,
      @Nullable Long version) {}

  /** One exact database-case-insensitive name match for an ITEM cost. */
  public record MaterialBridge(
      @NotNull UUID ingredientId,
      @NotNull UUID materialId,
      @Nullable QuantityType quantityType,
      @Nullable Long version) {}

  /**
   * The surviving item resolved from a submitted local identity; {@code retired} holds when a new
   * line may not take it, because the P4K retired it or an admin hid it as not obtainable.
   */
  public record OrderedItem(
      @NotNull UUID requestedId,
      @NotNull UUID canonicalId,
      boolean retired,
      @Nullable Long version) {}
}

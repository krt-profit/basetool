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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Blueprint;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kBlueprintDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reconciles the catalogue's {@code blueprints} with {@code blueprint}: match by P4K GUID, SC Wiki
 * UUID and {@code scwiki_key}, fill the empty key, output item and craft time, stamp, and link the
 * still-unresolved ingredient lines of a matched blueprint. An unmatched blueprint creates nothing.
 */
@Component
@RequiredArgsConstructor
public class P4kBlueprintReconciler {

  private final BlueprintRepository blueprintRepository;
  private final GameItemRepository gameItemRepository;
  private final MaterialRepository materialRepository;

  /**
   * Reconciles every blueprint; the items must be reconciled first.
   *
   * @param blueprints the catalogue's blueprints
   * @param context the run
   * @return the ingredient lines whose material or item the run resolved
   */
  public int reconcile(
      @NotNull List<P4kBlueprintDto> blueprints, @NotNull P4kImportContext context) {
    P4kRowMatcher<Blueprint> matcher =
        new P4kRowMatcher<>(
            P4kKind.BLUEPRINT,
            blueprintRepository::findByP4kUuid,
            blueprintRepository::findByScwikiUuid,
            Blueprint::getScwikiUuid,
            "scwiki_uuid",
            key ->
                blueprintRepository
                    .findFirstByScwikiKeyOrderByScwikiUuidAsc(key)
                    .map(List::of)
                    .orElseGet(List::of),
            _ -> List.of(),
            _ -> false);
    int ingredientsResolved = 0;
    for (P4kBlueprintDto blueprint : blueprints) {
      P4kRowMatcher.Match<Blueprint> match =
          matcher.match(blueprint, blueprint.key(), null, false, context);
      if (match instanceof P4kRowMatcher.Matched<Blueprint>(Blueprint row, UUID guid)) {
        boolean apply = context.isApply();
        boolean enriched =
            P4kFields.fillIfNull(row.getScwikiKey(), blueprint.key(), row::setScwikiKey, apply);
        enriched |=
            P4kFields.fillIfNull(
                row.getOutputItem(),
                producedItem(blueprint.producedItemGuid()),
                row::setOutputItem,
                apply);
        enriched |=
            P4kFields.fillIfNull(
                row.getCraftTimeSeconds(),
                blueprint.craftTimeSeconds(),
                row::setCraftTimeSeconds,
                apply);
        context.stamp(row, guid);
        context.getReport().matched(P4kKind.BLUEPRINT, enriched);
        ingredientsResolved += resolveIngredients(row, apply);
      } else if (match instanceof P4kRowMatcher.Unmatched<Blueprint>) {
        context.getReport().unmatched(P4kKind.BLUEPRINT, blueprint);
      }
    }
    return ingredientsResolved;
  }

  /**
   * Finds the item a blueprint produces, by P4K GUID and then by SC Wiki UUID.
   *
   * @param producedItemGuid the produced item's GUID text, or {@code null}
   * @return the item, or {@code null}
   */
  @Nullable
  private GameItem producedItem(@Nullable String producedItemGuid) {
    UUID guid = P4kGuids.parse(producedItemGuid);
    if (guid == null) {
      return null;
    }
    return gameItemRepository
        .findByP4kUuid(guid)
        .or(() -> gameItemRepository.findByExternalUuid(guid))
        .orElse(null);
  }

  /**
   * Links the ingredient lines that hold a SC Wiki UUID but no material or item yet.
   *
   * @param blueprint the matched blueprint
   * @param apply whether to write the link or only count it
   * @return the lines resolved, or in a preview resolvable
   */
  private int resolveIngredients(@NotNull Blueprint blueprint, boolean apply) {
    int resolved = 0;
    for (BlueprintIngredient ingredient : blueprint.getIngredients()) {
      if (ingredient.getKind() == BlueprintIngredientKind.RESOURCE
          && ingredient.getMaterial() == null
          && ingredient.getWikiResourceUuid() != null) {
        Material material =
            materialRepository.findByScwikiUuid(ingredient.getWikiResourceUuid()).orElse(null);
        if (material != null) {
          if (apply) {
            ingredient.setMaterial(material);
          }
          resolved++;
        }
      } else if (ingredient.getKind() == BlueprintIngredientKind.ITEM
          && ingredient.getGameItem() == null
          && ingredient.getWikiItemUuid() != null) {
        GameItem item =
            gameItemRepository.findByExternalUuid(ingredient.getWikiItemUuid()).orElse(null);
        if (item != null) {
          if (apply) {
            ingredient.setGameItem(item);
          }
          resolved++;
        }
      }
    }
    return resolved;
  }
}

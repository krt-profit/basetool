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
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintCategory;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredient;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintIngredientKind;
import de.greluc.krt.profit.basetool.backend.catalogue.api.BlueprintRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.P4kKeyedRow;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kBlueprintDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogue;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kDismantleProcessDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kGuids;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kKind;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reconciles the catalogue's {@code blueprints} with {@code blueprint} (REQ-P4K-027…029, ADR-0340):
 * match by P4K GUID, SC Wiki UUID and {@code scwiki_key}; the P4K leads the produced item and its
 * GUID, the category, the craft time, {@code defaultAvailable}, the dismantle time and efficiency,
 * the recipe of the first tier and the dismantle returns. With the run's seeding box ticked an
 * unmatched blueprint is created when its produced item exists or the run creates it; every other
 * unmatched blueprint is reported {@code UNMATCHED}.
 */
@Component
@RequiredArgsConstructor
public class P4kBlueprintReconciler {

  private final BlueprintRepository blueprintRepository;
  private final MaterialRepository materialRepository;
  private final P4kReviewCaseRepository reviewCaseRepository;

  /**
   * The values the P4K leads on one blueprint.
   *
   * @param producedItem the produced item the run resolved, or {@code null}
   * @param producedItemGuid the produced item's GUID, or {@code null}
   * @param category the blueprint category the run resolved, or {@code null}
   * @param craftTimeSeconds the first tier's craft time, or {@code null}
   * @param defaultAvailable whether a player has the blueprint by default, or {@code null}
   * @param dismantle the dismantle process, or {@code null}
   * @param recipe the first tier's recipe, or {@code null} without a tier
   * @param returns the dismantle returns, or {@code null} without a dismantle
   */
  private record Values(
      @Nullable GameItem producedItem,
      @Nullable UUID producedItemGuid,
      @Nullable BlueprintCategory category,
      @Nullable Integer craftTimeSeconds,
      @Nullable Boolean defaultAvailable,
      @Nullable P4kDismantleProcessDto dismantle,
      @Nullable P4kBlueprintRecipe recipe,
      @Nullable List<P4kBlueprintRecipe.Return> returns) {

    /**
     * Writes the P4K's own values of the led columns, a {@code null} included.
     *
     * @param row the blueprint
     */
    void mirrorOn(@NotNull Blueprint row) {
      row.setP4kCraftTimeSeconds(craftTimeSeconds);
      row.setP4kDefaultAvailable(defaultAvailable);
      row.setP4kDismantleTimeSeconds(dismantle == null ? null : dismantle.timeSeconds());
      row.setP4kDismantleEfficiency(dismantle == null ? null : dismantle.efficiency());
    }
  }

  /**
   * Reconciles every blueprint; the items, the commodities and the blueprint categories must be
   * reconciled first.
   *
   * @param catalogue the catalogue
   * @param context the run
   * @return the recipe lines whose material or item the run resolved
   */
  public int reconcile(@NotNull P4kCatalogue catalogue, @NotNull P4kImportContext context) {
    P4kRowIndex<Blueprint> stored =
        new P4kRowIndex<>(
            blueprintRepository.findAll(),
            Blueprint::getScwikiUuid,
            Blueprint::getScwikiKey,
            _ -> null);
    P4kRowMatcher<Blueprint> matcher =
        new P4kRowMatcher<>(
            P4kKind.BLUEPRINT,
            stored::byP4kUuid,
            stored::byWikiUuid,
            Blueprint::getScwikiUuid,
            "scwiki_uuid",
            key -> firstOfKey(stored, key),
            _ -> List.of(),
            _ -> false);
    P4kRecipeLinks links =
        new P4kRecipeLinks(
            context, P4kCommodityJoin.of(catalogue), materialRepository, reviewCaseRepository);
    Map<UUID, P4kKeyedRow> items = context.stampedRows(P4kKind.ITEM);
    int resolved = 0;
    for (P4kBlueprintDto blueprint : catalogue.blueprints()) {
      P4kBlueprintRecipeReader reader =
          new P4kBlueprintRecipeReader(blueprint, catalogue.crafting(), links, context);
      P4kRowMatcher.Match<Blueprint> match =
          matcher.match(blueprint, blueprint.key(), null, true, context);
      if (match instanceof P4kRowMatcher.Matched<Blueprint>(Blueprint row, UUID guid)) {
        Values values = values(blueprint, reader, items, context);
        boolean changed = enrich(row, blueprint, values, context);
        context.stamp(P4kKind.BLUEPRINT, blueprint, row, guid);
        stored.add(row);
        context.getReport().matched(P4kKind.BLUEPRINT, changed);
        resolved +=
            values.recipe() == null
                ? resolveWikiIngredients(row, context)
                : values.recipe().resolvedLines();
      } else if (match instanceof P4kRowMatcher.Unmatched<Blueprint>(UUID guid)) {
        resolved +=
            unmatched(blueprint, guid, values(blueprint, reader, items, context), stored, context);
      }
    }
    return resolved;
  }

  /**
   * Finds the blueprint whose SC Wiki key equals a key exactly, the one with the lowest SC Wiki
   * UUID when several share it and one without a UUID last, as PostgreSQL orders {@code uuid}
   * values.
   *
   * @param stored the blueprint rows of the run
   * @param key the record's key
   * @return that one blueprint, or an empty list
   */
  @NotNull
  private static List<Blueprint> firstOfKey(
      @NotNull P4kRowIndex<Blueprint> stored, @NotNull String key) {
    return stored.byClassName(key).stream()
        .filter(row -> key.equals(row.getScwikiKey()))
        .min(
            Comparator.comparing(
                Blueprint::getScwikiUuid,
                Comparator.nullsLast(Comparator.comparing(UUID::toString))))
        .map(List::of)
        .orElseGet(List::of);
  }

  /**
   * Reads the values the P4K leads on one blueprint.
   *
   * @param blueprint the record
   * @param reader the record's recipe reader
   * @param items the items the run matched or created, by GUID
   * @param context the run
   * @return the values
   */
  @NotNull
  private Values values(
      @NotNull P4kBlueprintDto blueprint,
      @NotNull P4kBlueprintRecipeReader reader,
      @NotNull Map<UUID, P4kKeyedRow> items,
      @NotNull P4kImportContext context) {
    UUID producedGuid = P4kGuids.parse(blueprint.producedItemGuid());
    return new Values(
        producedItem(producedGuid, items, context),
        producedGuid,
        context.blueprintCategory(blueprint.categoryGuid()),
        blueprint.craftTimeSeconds(),
        blueprint.defaultAvailable(),
        reader.process(),
        reader.recipe(),
        reader.returns());
  }

  /**
   * Writes the led values onto a matched blueprint, reporting each change.
   *
   * @param row the blueprint
   * @param blueprint the record
   * @param values the P4K's values
   * @param context the run
   * @return whether a field changes
   */
  private boolean enrich(
      @NotNull Blueprint row,
      @NotNull P4kBlueprintDto blueprint,
      @NotNull Values values,
      @NotNull P4kImportContext context) {
    P4kRowWriter writer = new P4kRowWriter(context, P4kKind.BLUEPRINT, blueprint);
    writer.fill("scwiki_key", row.getScwikiKey(), blueprint.key(), row::setScwikiKey);
    writer.lead(
        "output_item_id",
        row.getOutputItem(),
        values.producedItem(),
        row::setOutputItem,
        GameItem::getName);
    writer.fill(
        "output_name",
        row.getOutputName(),
        values.producedItem() == null ? null : values.producedItem().getName(),
        row::setOutputName);
    writer.lead(
        "produced_item_guid",
        row.getProducedItemGuid(),
        values.producedItemGuid(),
        row::setProducedItemGuid);
    writer.lead(
        "category_id",
        row.getCategory(),
        values.category(),
        row::setCategory,
        BlueprintCategory::displayName);
    writer.lead(
        "craft_time_seconds",
        row.getCraftTimeSeconds(),
        values.craftTimeSeconds(),
        row::setCraftTimeSeconds);
    writer.lead(
        "is_available_by_default",
        row.getIsAvailableByDefault(),
        values.defaultAvailable(),
        row::setIsAvailableByDefault);
    P4kDismantleProcessDto dismantle = values.dismantle();
    writer.lead(
        "dismantle_time_seconds",
        row.getDismantleTimeSeconds(),
        dismantle == null ? null : dismantle.timeSeconds(),
        row::setDismantleTimeSeconds);
    writer.lead(
        "dismantle_efficiency",
        row.getDismantleEfficiency(),
        dismantle == null ? null : dismantle.efficiency(),
        row::setDismantleEfficiency);
    if (context.isApply()) {
      values.mirrorOn(row);
    }
    boolean changed = writer.isChanged();
    changed |= writeRecipe(row, blueprint, values.recipe(), context);
    changed |= writeReturns(row, blueprint, values.returns(), context);
    return changed;
  }

  /**
   * Replaces a stored recipe that differs from the P4K's, reporting it.
   *
   * @param row the blueprint
   * @param blueprint the record
   * @param recipe the P4K's recipe, or {@code null} to keep the stored one
   * @param context the run
   * @return whether the recipe changes
   */
  private static boolean writeRecipe(
      @NotNull Blueprint row,
      @NotNull P4kBlueprintDto blueprint,
      @Nullable P4kBlueprintRecipe recipe,
      @NotNull P4kImportContext context) {
    if (recipe == null) {
      return false;
    }
    P4kBlueprintRecipe stored = P4kBlueprintRecipe.stored(row);
    if (recipe.equals(stored)) {
      return false;
    }
    context
        .getReport()
        .changed(
            P4kKind.BLUEPRINT,
            blueprint,
            "requirement_groups",
            stored == null ? null : stored.summary(),
            recipe.summary());
    if (context.isApply()) {
      recipe.writeTo(row);
    }
    return true;
  }

  /**
   * Replaces stored dismantle returns that differ from the P4K's, reporting them.
   *
   * @param row the blueprint
   * @param blueprint the record
   * @param returns the P4K's returns, or {@code null} to keep the stored ones
   * @param context the run
   * @return whether the returns change
   */
  private static boolean writeReturns(
      @NotNull Blueprint row,
      @NotNull P4kBlueprintDto blueprint,
      @Nullable List<P4kBlueprintRecipe.Return> returns,
      @NotNull P4kImportContext context) {
    if (returns == null) {
      return false;
    }
    List<P4kBlueprintRecipe.Return> stored = P4kBlueprintRecipe.storedReturns(row);
    if (returns.equals(stored)) {
      return false;
    }
    context
        .getReport()
        .changed(
            P4kKind.BLUEPRINT,
            blueprint,
            "dismantle_returns",
            String.valueOf(stored.size()),
            String.valueOf(returns.size()));
    if (context.isApply()) {
      P4kBlueprintRecipe.writeReturns(row, returns);
    }
    return true;
  }

  /**
   * Creates an unmatched blueprint when the run's seeding box is ticked and its produced item
   * exists or the run creates it (REQ-P4K-028), else reports it unmatched.
   *
   * @param blueprint the record
   * @param guid the record's GUID, or {@code null}
   * @param values the P4K's values
   * @param stored the blueprint rows of the run, extended by the created one
   * @param context the run
   * @return the recipe lines of the created blueprint whose material or item resolved
   */
  private int unmatched(
      @NotNull P4kBlueprintDto blueprint,
      @Nullable UUID guid,
      @NotNull Values values,
      @NotNull P4kRowIndex<Blueprint> stored,
      @NotNull P4kImportContext context) {
    UUID producedGuid = values.producedItemGuid();
    boolean producible =
        values.producedItem() != null
            || (producedGuid != null && context.getReport().isAdded(P4kKind.ITEM, producedGuid));
    if (guid == null || !context.isCreateItemsAndShips() || !producible) {
      context.getReport().unmatched(P4kKind.BLUEPRINT, blueprint);
      return 0;
    }
    if (context.isApply()) {
      Blueprint row = new Blueprint();
      row.setScwikiKey(blueprint.key());
      row.setOutputItem(values.producedItem());
      row.setOutputName(values.producedItem() == null ? null : values.producedItem().getName());
      row.setProducedItemGuid(producedGuid);
      row.setCategory(values.category());
      row.setCraftTimeSeconds(values.craftTimeSeconds());
      row.setIsAvailableByDefault(Boolean.TRUE.equals(values.defaultAvailable()));
      if (values.dismantle() != null) {
        row.setDismantleTimeSeconds(values.dismantle().timeSeconds());
        row.setDismantleEfficiency(values.dismantle().efficiency());
      }
      values.mirrorOn(row);
      if (values.recipe() != null) {
        values.recipe().writeTo(row);
      }
      if (values.returns() != null) {
        P4kBlueprintRecipe.writeReturns(row, values.returns());
      }
      context.stamp(P4kKind.BLUEPRINT, blueprint, row, guid);
      stored.add(blueprintRepository.save(row));
    }
    context.getReport().added(P4kKind.BLUEPRINT, blueprint);
    return values.recipe() == null ? 0 : values.recipe().resolvedLines();
  }

  /**
   * Finds the item a blueprint produces: the item the run matched or created, else the stored item
   * of that P4K GUID or SC Wiki UUID.
   *
   * @param guid the produced item's GUID, or {@code null}
   * @param items the items the run matched or created, by GUID
   * @param context the run
   * @return the item, or {@code null}
   */
  @Nullable
  private static GameItem producedItem(
      @Nullable UUID guid,
      @NotNull Map<UUID, P4kKeyedRow> items,
      @NotNull P4kImportContext context) {
    if (guid == null) {
      return null;
    }
    if (items.get(guid) instanceof GameItem item) {
      return item;
    }
    P4kRowIndex<GameItem> rows = context.itemRows();
    return rows.byP4kUuid(guid).or(() -> rows.byWikiUuid(guid)).orElse(null);
  }

  /**
   * Links the ingredient lines of a recipe the Wiki wrote that hold a SC Wiki UUID but no material
   * or item yet; a recipe the P4K wrote resolves its lines itself.
   *
   * @param blueprint the matched blueprint
   * @param context the run; a preview only counts the links
   * @return the lines resolved, or in a preview resolvable
   */
  private static int resolveWikiIngredients(
      @NotNull Blueprint blueprint, @NotNull P4kImportContext context) {
    boolean apply = context.isApply();
    int resolved = 0;
    for (BlueprintIngredient ingredient : blueprint.getIngredients()) {
      if (ingredient.getKind() == BlueprintIngredientKind.RESOURCE
          && ingredient.getMaterial() == null
          && ingredient.getWikiResourceUuid() != null) {
        Material material =
            context.commodityRows().byWikiUuid(ingredient.getWikiResourceUuid()).orElse(null);
        if (material != null) {
          if (apply) {
            ingredient.setMaterial(material);
          }
          resolved++;
        }
      } else if (ingredient.getKind() == BlueprintIngredientKind.ITEM
          && ingredient.getGameItem() == null
          && ingredient.getWikiItemUuid() != null) {
        GameItem item = context.itemRows().byWikiUuid(ingredient.getWikiItemUuid()).orElse(null);
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

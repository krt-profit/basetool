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

import de.greluc.krt.profit.basetool.backend.catalogue.internal.p4k.P4kCatalogue;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/**
 * Hands each kind of a catalogue to its reconciler in dependency order (REQ-P4K-007…044):
 * manufacturers before items and ships, resource groups before commodities, items, commodities and
 * blueprint categories before blueprints, the base kinds before the paints and loadouts that refer
 * to them, the starmap tree before the UEX locations' links to it, and the shops before their
 * terminal links.
 */
@Component
@RequiredArgsConstructor
class P4kReconcilers {

  private final P4kManufacturerReconciler manufacturerReconciler;
  private final P4kItemReconciler itemReconciler;
  private final P4kItemStatReconciler itemStatReconciler;
  private final P4kShipReconciler shipReconciler;
  private final P4kResourceGroupReconciler resourceGroupReconciler;
  private final P4kCommodityReconciler commodityReconciler;
  private final P4kMaterialGroupReview materialGroupReview;
  private final P4kBlueprintCategoryReconciler blueprintCategoryReconciler;
  private final P4kBlueprintReconciler blueprintReconciler;
  private final P4kPaintReconciler paintReconciler;
  private final P4kLoadoutReconciler loadoutReconciler;
  private final P4kStarmapReconciler starmapReconciler;
  private final P4kStarmapLinker starmapLinker;
  private final P4kShopReconciler shopReconciler;
  private final P4kShopLinker shopLinker;

  /**
   * Reconciles every kind of a catalogue: the base kinds, the resource groups and the blueprint
   * categories, then the paints and the ship loadouts (REQ-P4K-040, REQ-P4K-041), the starmap tree
   * and its UEX links when the catalogue lists starmap objects (REQ-P4K-034, REQ-P4K-035), and the
   * shops with their item lists and terminal links when it lists shops (REQ-P4K-042…044).
   *
   * @param catalogue the catalogue
   * @param loadouts streams the catalogue's loadouts, which it does not hold in memory
   * @param context the run
   * @return the ingredient lines the blueprints resolved
   */
  int reconcile(
      @NotNull P4kCatalogue catalogue,
      @NotNull P4kLoadoutReconciler.LoadoutStream loadouts,
      @NotNull P4kImportContext context) {
    manufacturerReconciler.reconcile(catalogue.manufacturers(), context);
    itemReconciler.reconcile(catalogue.items(), context);
    itemStatReconciler.reconcile(catalogue, context);
    shipReconciler.reconcile(catalogue, context);
    manufacturerReconciler.finish(context);
    resourceGroupReconciler.reconcile(catalogue, context);
    context.setAdminCategoriesRetired(
        materialGroupReview.review(commodityReconciler.reconcile(catalogue, context), context));
    blueprintCategoryReconciler.reconcile(catalogue.blueprintCategories(), context);
    final int ingredientsResolved = blueprintReconciler.reconcile(catalogue, context);
    paintReconciler.reconcile(
        catalogue.paints(), catalogue.head().isComplete(P4kCatalogue.PAINTS), context);
    loadoutReconciler.reconcile(loadouts, catalogue, context);
    if (!catalogue.starmapObjects().isEmpty()) {
      starmapLinker.link(starmapReconciler.reconcile(catalogue.starmapObjects(), context), context);
    }
    if (!catalogue.shops().isEmpty()) {
      shopLinker.link(shopReconciler.reconcile(catalogue.shops(), context), context);
    }
    return ingredientsResolved;
  }
}

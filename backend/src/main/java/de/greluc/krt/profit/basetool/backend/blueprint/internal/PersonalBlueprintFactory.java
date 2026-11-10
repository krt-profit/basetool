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

package de.greluc.krt.profit.basetool.backend.blueprint.internal;

import de.greluc.krt.profit.basetool.backend.blueprint.api.BlueprintSource;
import de.greluc.krt.profit.basetool.backend.blueprint.api.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.blueprint.api.ResolvedProduct;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import java.time.Instant;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** Creates transient owned blueprints from a resolved product and explicit provenance. */
public final class PersonalBlueprintFactory {
  private PersonalBlueprintFactory() {}

  /**
   * Stamps an owned blueprint, resolving its output item as a canonical lazy reference.
   *
   * @param items the canonical item repository
   * @param owner the owning member
   * @param product the resolved product
   * @param acquiredAt optional acquisition instant
   * @param note optional owner note
   * @param source acquisition provenance
   * @return the unsaved blueprint
   */
  public static @NotNull PersonalBlueprint create(
      @NotNull GameItemRepository items,
      @NotNull UUID owner,
      @NotNull ResolvedProduct product,
      @Nullable Instant acquiredAt,
      @Nullable String note,
      @NotNull BlueprintSource source) {
    PersonalBlueprint entity = new PersonalBlueprint();
    entity.setOwnerUserId(owner);
    entity.setProductKey(product.productKey());
    entity.setProductName(product.productName());
    if (product.outputItemId() != null) {
      entity.setOutputItem(items.getCanonicalReferenceById(product.outputItemId()));
    }
    entity.setAcquiredAt(acquiredAt);
    entity.setNote(note);
    entity.setSource(source);
    return entity;
  }
}

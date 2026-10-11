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

package de.greluc.krt.profit.basetool.backend.blueprint.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** The craftable products the blueprint catalogue knows, for other modules (plan §5.3). */
public interface BlueprintProducts {

  /**
   * Resolves a normalized product key to its canonical product.
   *
   * @param productKey the normalized product key, or {@code null}
   * @return the product, or empty when the key is blank or names no active product
   */
  @NotNull
  Optional<ResolvedProduct> resolveByProductKey(@Nullable String productKey);

  /**
   * Lists every active product.
   *
   * @return the products, never {@code null}
   */
  @NotNull
  List<ResolvedProduct> allProducts();

  /**
   * Resolves the product an item offer names from the game item it produces (REQ-MARKET-014): of
   * the active blueprints producing the item, the lowest normalized product key.
   *
   * @param gameItemId the game item, or {@code null}
   * @return the product, or empty if the id is {@code null} or no active blueprint produces the
   *     item
   */
  @NotNull
  Optional<ResolvedProduct> resolveByGameItem(@Nullable UUID gameItemId);

  /**
   * Indexes every active recipe's lower-cased {@code scwiki_key} to the normalized product keys it
   * produces (REQ-XCH-012).
   *
   * @return the index in first-seen order, never {@code null}
   */
  @NotNull
  Map<String, Set<String>> scwikiKeyToProductKeys();
}

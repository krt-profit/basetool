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

package de.greluc.krt.profit.basetool.backend.materialexchange.internal;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.blueprint.api.ResolvedProduct;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import java.util.UUID;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The item an item offer or request names (ADR-0371): the blueprint product of an item an active
 * blueprint produces, or the catalogue row of an item no blueprint produces, with the display name
 * the entry snapshots. Exactly one of {@code productKey} and {@code gameItemId} is set.
 *
 * @param productKey the normalized blueprint product key, or {@code null} for a catalogue row
 * @param gameItemId the catalogue row, or {@code null} for a blueprint product
 * @param name the display name
 */
record ExchangeItemSubject(
    @Nullable String productKey, @Nullable UUID gameItemId, @NotNull String name) {

  /**
   * Names a blueprint product.
   *
   * @param product the resolved product
   * @return the product's subject
   */
  @NotNull
  static ExchangeItemSubject of(@NotNull ResolvedProduct product) {
    return new ExchangeItemSubject(product.productKey(), null, product.productName());
  }

  /**
   * Names a catalogue row no blueprint produces.
   *
   * @param item the catalogue row
   * @return the row's subject
   */
  @NotNull
  static ExchangeItemSubject of(@NotNull GameItem item) {
    return new ExchangeItemSubject(null, item.getId(), item.getName());
  }

  /**
   * Writes the identity and the display name onto an item offer.
   *
   * @param offer the item offer
   */
  void applyTo(@NotNull MaterialExchangeOffer offer) {
    offer.setItemProductKey(productKey);
    offer.setItemGameItemId(gameItemId);
    offer.setItemName(name);
  }

  /**
   * Writes the identity and the display name onto an item request.
   *
   * @param request the item request
   */
  void applyTo(@NotNull MaterialExchangeRequest request) {
    request.setItemProductKey(productKey);
    request.setItemGameItemId(gameItemId);
    request.setItemName(name);
  }

  /**
   * Adds the identity to an audit payload, as {@code product} or as {@code gameItem}.
   *
   * @param details the payload so far
   * @return the payload with the identity
   */
  @NotNull
  @Contract(pure = true)
  AuditDetails withIdentity(@NotNull AuditDetails details) {
    return productKey != null
        ? details.with("product", productKey)
        : details.with("gameItem", gameItemId);
  }
}

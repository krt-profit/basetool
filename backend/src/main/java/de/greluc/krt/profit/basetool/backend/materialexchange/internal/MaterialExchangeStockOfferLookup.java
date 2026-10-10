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

import de.greluc.krt.profit.basetool.backend.inventory.api.StockOfferLookup;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

/** The Materialbörse's answer to the Lager's {@link StockOfferLookup} (REQ-MARKET-013). */
@Component
@RequiredArgsConstructor
public class MaterialExchangeStockOfferLookup implements StockOfferLookup {

  private final MaterialExchangeOfferRepository offerRepository;

  @Override
  public boolean isOffered(@NotNull UUID inventoryItemId) {
    return offerRepository.existsByInventoryItemId(inventoryItemId);
  }

  @Override
  public double activeOfferedAmount(@NotNull UUID inventoryItemId) {
    return offerRepository
        .findByInventoryItemIdAndStatus(inventoryItemId, MaterialExchangeOfferStatus.ACTIVE)
        .map(MaterialExchangeStockOfferLookup::offered)
        .orElse(0.0);
  }

  /**
   * Reads the promised amount of one offer.
   *
   * @param offer the active offer
   * @return its SCU for a material offer, its units for an item offer, {@code 0} when neither is
   *     set
   */
  private static double offered(@NotNull MaterialExchangeOffer offer) {
    if (offer.getOfferedAmount() != null) {
      return offer.getOfferedAmount();
    }
    return offer.getItemQuantity() != null ? offer.getItemQuantity() : 0.0;
  }
}

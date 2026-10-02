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

package de.greluc.krt.profit.basetool.backend.model.projection;

import java.util.UUID;

/**
 * One lot of a member's stock for the exchange: the member's rows, personal and shared, of one
 * material or item at one location, quality and stolen state, summed across org-unit pools
 * (REQ-XCH-016).
 */
public interface ExchangeStockLotRow {

  /**
   * The lowest row id in the lot, which orders the snapshot.
   *
   * @return the id
   */
  UUID getAnchor();

  /**
   * The lot's key, as the change feed records it.
   *
   * @return the key
   */
  String getLotKey();

  /**
   * The material, or {@code null} for an item lot.
   *
   * @return the material id
   */
  UUID getMaterialId();

  /**
   * The material's name, or {@code null} for an item lot.
   *
   * @return the name
   */
  String getMaterialName();

  /**
   * The material's refining type, or {@code null} for an item lot.
   *
   * @return {@code RAW}, {@code REFINED} or {@code NO_REFINE}
   */
  String getMaterialType();

  /**
   * Whether the material is a UEX commodity, or {@code null} for an item lot.
   *
   * @return whether it is a commodity
   */
  Boolean getCommodity();

  /**
   * The material's UEX mineral flag, or {@code null} when UEX does not know it.
   *
   * @return {@code 1}, {@code 0} or {@code null}
   */
  Integer getMineral();

  /**
   * The material's UEX harvestable flag, or {@code null} when UEX does not know it.
   *
   * @return {@code 1}, {@code 0} or {@code null}
   */
  Integer getHarvestable();

  /**
   * The material's UEX raw flag, or {@code null} when UEX does not know it.
   *
   * @return {@code 1}, {@code 0} or {@code null}
   */
  Integer getRaw();

  /**
   * The material's UEX refined flag, or {@code null} when UEX does not know it.
   *
   * @return {@code 1}, {@code 0} or {@code null}
   */
  Integer getRefined();

  /**
   * The material's UEX buyable flag, or {@code null} when UEX does not know it.
   *
   * @return {@code 1}, {@code 0} or {@code null}
   */
  Integer getBuyable();

  /**
   * The material's UEX sellable flag, or {@code null} when UEX does not know it.
   *
   * @return {@code 1}, {@code 0} or {@code null}
   */
  Integer getSellable();

  /**
   * The material's unit, or {@code null} for an item lot.
   *
   * @return {@code SCU} or {@code PIECE}
   */
  String getQuantityType();

  /**
   * The item, or {@code null} for a material lot.
   *
   * @return the item id
   */
  UUID getGameItemId();

  /**
   * The item's name, or {@code null} for a material lot.
   *
   * @return the name
   */
  String getGameItemName();

  /**
   * The location's name.
   *
   * @return the name
   */
  String getLocationName();

  /**
   * The UEX id of the location's city, or {@code null}.
   *
   * @return the id
   */
  Integer getUexCityId();

  /**
   * The UEX id of the location's space station, or {@code null}.
   *
   * @return the id
   */
  Integer getUexSpaceStationId();

  /**
   * The quality, {@code 0} for an item lot.
   *
   * @return the quality
   */
  int getQuality();

  /**
   * Whether the lot is marked stolen.
   *
   * @return whether it is stolen
   */
  boolean getStolen();

  /**
   * The summed amount.
   *
   * @return the amount
   */
  double getAmount();
}

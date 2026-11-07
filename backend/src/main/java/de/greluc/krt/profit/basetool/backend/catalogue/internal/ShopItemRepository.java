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

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads and stores the items of the shops' known item lists (REQ-P4K-044). */
public interface ShopItemRepository extends JpaRepository<ShopItem, ShopItem.Key> {

  /**
   * Reads every link as a key pair, without loading an entity.
   *
   * @return one pair per stored link
   */
  @Query("SELECT si.shopId AS shopId, si.gameItemId AS gameItemId FROM ShopItem si")
  @NotNull
  List<ShopItemLink> findAllLinks();

  /**
   * Deletes some of one shop's links in one statement.
   *
   * @param shopId the shop
   * @param gameItemIds the items whose links go; never empty
   * @return the number of deleted links
   */
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM ShopItem si WHERE si.shopId = :shopId AND si.gameItemId IN :gameItemIds")
  int deleteLinks(
      @Param("shopId") @NotNull UUID shopId,
      @Param("gameItemIds") @NotNull Collection<UUID> gameItemIds);

  /** One stored link of a shop to an item. */
  interface ShopItemLink {

    /**
     * Returns the shop.
     *
     * @return the shop's id
     */
    UUID getShopId();

    /**
     * Returns the item the shop's list names.
     *
     * @return the item's id
     */
    UUID getGameItemId();
  }
}

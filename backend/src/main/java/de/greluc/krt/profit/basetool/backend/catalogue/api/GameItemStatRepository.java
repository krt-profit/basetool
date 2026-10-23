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

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Spring Data repository for {@link GameItemStat}, the stored blueprint stat base values. */
@Repository
public interface GameItemStatRepository extends JpaRepository<GameItemStat, UUID> {

  /**
   * Returns every stored base value of one game item.
   *
   * @param gameItemId the game item's id
   * @return the item's stat rows, empty when none is stored
   */
  List<GameItemStat> findByGameItemId(UUID gameItemId);

  /**
   * Returns the in-game UUIDs of the items active blueprints craft that have no stored base value
   * yet, so the item sync can fetch their full Wiki payload.
   *
   * @return distinct external UUIDs of output items without a {@code game_item_stat} row
   */
  @Query(
      "SELECT DISTINCT g.externalUuid FROM Blueprint b JOIN b.outputItem g"
          + " WHERE b.scwikiDeletedAt IS NULL AND g.externalUuid IS NOT NULL"
          + " AND NOT EXISTS (SELECT 1 FROM GameItemStat s WHERE s.gameItem = g)")
  List<UUID> findBlueprintOutputUuidsWithoutStats();

  /**
   * Counts the game items that carry at least one stored base value.
   *
   * @return the number of distinct items with a {@code game_item_stat} row
   */
  @Query("SELECT COUNT(DISTINCT s.gameItem) FROM GameItemStat s")
  long countItemsWithStats();
}

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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Reads the amounts a selection of "Mein Lager" entries holds, for its sale value. */
@NoRepositoryBean
interface InventorySaleSliceQueries extends Repository<InventoryItem, UUID> {

  /**
   * Sums the member's own marked material entries per material and „gestohlen" marker
   * (REQ-INV-072). An id of another member's entry, of a game-item entry or of no entry adds
   * nothing.
   *
   * @param userId the member who owns every counted entry
   * @param itemIds the marked entry ids; never empty
   * @return one slice per material and marker
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.inventory.internal.InventorySaleSlice(m.id,
      m.quantityType, i.stolen, SUM(COALESCE(i.amount, 0.0))) FROM InventoryItem i
      JOIN i.material m
      WHERE i.user.id = :userId AND i.id IN :itemIds
      GROUP BY m.id, m.quantityType, i.stolen
      """)
  List<InventorySaleSlice> findSaleSlices(
      @Param("userId") UUID userId, @Param("itemIds") Collection<UUID> itemIds);
}

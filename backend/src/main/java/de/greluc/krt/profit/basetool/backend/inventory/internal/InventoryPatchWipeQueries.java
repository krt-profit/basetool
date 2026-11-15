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

import de.greluc.krt.profit.basetool.backend.inventory.api.StockWipeRow;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Reads the stock rows a patch wipe selects from, with the catalogue facts its selection reads
 * (REQ-PATCH-012).
 */
@NoRepositoryBean
interface InventoryPatchWipeQueries extends Repository<InventoryItem, UUID> {

  /** The projection of a row onto {@link StockWipeRow}, joined to its material or game item. */
  String WIPE_ROW =
      """
      SELECT new de.greluc.krt.profit.basetool.backend.inventory.api.StockWipeRow(
          i.id, i.user.id, m.id, m.type, g.id, g.wikiType, g.wikiSubType, g.classification, g.kind)
      FROM InventoryItem i LEFT JOIN i.material m LEFT JOIN i.gameItem g
      """;

  /**
   * Lists every row created before the cutoff; a row without a creation time counts as created
   * before it.
   *
   * @param cutoff the patch's live time
   * @return the rows, ascending by id
   */
  @Query(WIPE_ROW + " WHERE i.createdAt < :cutoff OR i.createdAt IS NULL ORDER BY i.id")
  List<StockWipeRow> findWipeRowsCreatedBefore(@Param("cutoff") Instant cutoff);

  /**
   * Reads the given rows with their catalogue facts.
   *
   * @param ids the rows, at most one query's worth
   * @return the rows that exist, ascending by id
   */
  @Query(WIPE_ROW + " WHERE i.id IN :ids ORDER BY i.id")
  List<StockWipeRow> findWipeRowsByIdIn(@Param("ids") Collection<UUID> ids);

  /**
   * Lists the members who hold the given rows.
   *
   * @param ids the rows, at most one query's worth
   * @return the distinct holders
   */
  @Query("SELECT DISTINCT i.user.id FROM InventoryItem i WHERE i.id IN :ids")
  List<UUID> findHolderIds(@Param("ids") Collection<UUID> ids);
}

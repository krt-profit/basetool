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
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.NoRepositoryBean;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Reads retained physical quantity provenance and its current correction resources. */
@NoRepositoryBean
interface InventoryQuantityQueries extends Repository<InventoryItem, UUID> {
  /**
   * Batch-fetches the current permitted correction-selection labels.
   *
   * @param ids known connected rows
   * @return rows with holder, location and pool labels fetched
   */
  @EntityGraph(attributePaths = {"user", "location", "owningOrgUnit"})
  @Query("SELECT i FROM InventoryItem i WHERE i.id IN :ids ORDER BY i.id")
  List<InventoryItem> findQuantityCorrectionRows(@Param("ids") Collection<UUID> ids);

  /**
   * Locates retained physical origins independently of storage-row identities.
   *
   * @param quantity exact known physical intervals as JSON
   * @return current rows overlapping the exact intervals
   */
  @Query(
      value =
          "SELECT DISTINCT i.id FROM inventory_item i CROSS JOIN"
              + " jsonb_array_elements(CAST(:quantity AS jsonb)) wanted WHERE i.quantity_provenance"
              + " @> jsonb_build_array(jsonb_build_object('origin', wanted->>'origin')) AND EXISTS"
              + " (SELECT 1 FROM jsonb_array_elements(i.quantity_provenance) actual WHERE"
              + " actual->>'origin' = wanted->>'origin' AND (actual->>'fromQuanta')::bigint <"
              + " (wanted->>'toQuanta')::bigint AND (wanted->>'fromQuanta')::bigint <"
              + " (actual->>'toQuanta')::bigint)",
      nativeQuery = true)
  List<UUID> findRowsWithQuantity(@Param("quantity") String quantity);
}

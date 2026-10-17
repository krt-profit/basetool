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

import de.greluc.krt.profit.basetool.backend.inventory.api.ExchangeStockSplitRow;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Reads how a member's exchange stock lots split into personal stock and pools (REQ-XCH-016). */
public interface InventoryStockSplitRepository extends Repository<InventoryItem, UUID> {

  /**
   * Returns the personal part and the member's own part of each org-unit pool of the member's lots
   * with the given keys; shared rows without a unit count as personal.
   *
   * @param member the member
   * @param keys the lot keys; never empty
   * @return one part per lot and pool, ordered by lot key, the personal part first
   */
  @Query(
      value =
          """
          SELECT parts.lot_key AS lotKey, parts.org_unit_id AS orgUnitId,
                 SUM(parts.amount) AS amount
          FROM (SELECT exchange_stock_lot_key(i.material_id, i.game_item_id, i.location_id,
                                              i.quality, i.stolen) AS lot_key,
                       CASE WHEN i.personal THEN NULL ELSE i.owning_org_unit_id END AS org_unit_id,
                       i.amount
                FROM inventory_item i
                WHERE i.user_id = :member) parts
          WHERE parts.lot_key IN (:keys)
          GROUP BY parts.lot_key, parts.org_unit_id
          ORDER BY parts.lot_key, parts.org_unit_id NULLS FIRST
          """,
      nativeQuery = true)
  List<ExchangeStockSplitRow> findExchangeLotSplits(
      @Param("member") UUID member, @Param("keys") Collection<String> keys);
}

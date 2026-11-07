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

import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;

/**
 * The native SQL of the exchange's stock and org-stock lots, which {@link InventoryItemRepository}
 * completes with a {@code WHERE} clause on the aliases {@code lots} and {@code l}.
 */
final class ExchangeLotSql {

  /**
   * The place columns of a lot, last in its select list: the curated name, the UEX ids and the
   * starmap GUID of the location aliased {@code l} (REQ-XCH-018).
   */
  private static final String PLACE =
      " l.name AS locationName, c.id_city AS uexCityId,"
          + " s.id_space_station AS uexSpaceStationId, "
          + LocationRepository.EXCHANGE_GUID
          + " AS locationGuid ";

  /** The joins of a lot's place columns onto the lot aliased {@code lots}. */
  private static final String PLACE_JOINS =
      """
      JOIN location l ON l.id = lots.location_id
      LEFT JOIN city c ON c.id = l.city_id
      LEFT JOIN space_station s ON s.id = l.space_station_id
      """;

  /** The lots of a member, personal and shared, with their material and game item. */
  private static final String LOTS_SOURCE =
      """
      FROM (SELECT CAST(MIN(CAST(i.id AS text)) AS uuid) AS anchor,
                   exchange_stock_lot_key(i.material_id, i.game_item_id, i.location_id,
                                          i.quality, i.stolen) AS lot_key,
                   i.material_id, i.game_item_id, i.location_id,
                   COALESCE(i.quality, 0) AS quality, i.stolen, SUM(i.amount) AS amount
            FROM inventory_item i
            WHERE i.user_id = :member
            GROUP BY i.material_id, i.game_item_id, i.location_id, i.quality, i.stolen) lots
      LEFT JOIN material m ON m.id = lots.material_id
      LEFT JOIN game_item g ON g.id = lots.game_item_id
      """;

  /** A member's lots, personal and shared, with what the exchange shows of them (REQ-XCH-016). */
  static final String LOTS =
      """
      SELECT lots.anchor AS anchor, lots.lot_key AS lotKey, lots.material_id AS materialId,
        m.name AS materialName, m.type AS materialType, m.quantity_type AS quantityType,
        (m.id IS NOT NULL AND m.id_commodity IS NOT NULL) AS commodity, m.is_raw AS raw,
        m.is_mineral AS mineral, m.is_harvestable AS harvestable, m.is_refined AS refined,
        m.is_buyable AS buyable, m.is_sellable AS sellable, lots.game_item_id AS gameItemId,
        g.name AS gameItemName, g.wiki_type AS gameItemWikiType, g.size_class AS gameItemSize,
        g.grade AS gameItemGrade, g.item_class AS gameItemClass, lots.quality AS quality,
        lots.stolen AS stolen, lots.amount AS amount,
      """
          + PLACE
          + LOTS_SOURCE
          + PLACE_JOINS;

  /** The shared lots of the given units' pools with their earmarks, material and game item. */
  private static final String ORG_LOTS_SOURCE =
      """
      FROM (SELECT CAST(MIN(CAST(i.id AS text)) AS uuid) AS anchor,
                   'u:' || i.owning_org_unit_id || '|'
                       || exchange_stock_lot_key(i.material_id, i.game_item_id, i.location_id,
                                                 COALESCE(i.quality, 0), i.stolen) AS lot_key,
                   i.owning_org_unit_id AS unit_id, i.material_id, i.game_item_id,
                   i.location_id, COALESCE(i.quality, 0) AS quality, i.stolen,
                   SUM(i.amount) AS amount,
                   COALESCE(SUM(jo.amount), 0) AS job_order_earmarked,
                   COALESCE(SUM(mi.amount), 0) AS mission_earmarked
            FROM inventory_item i
            LEFT JOIN LATERAL (SELECT SUM(a.amount) AS amount
                               FROM inventory_item_job_order_allocation a
                               WHERE a.inventory_item_id = i.id) jo ON TRUE
            LEFT JOIN LATERAL (SELECT SUM(a.amount) AS amount
                               FROM inventory_item_mission_allocation a
                               WHERE a.inventory_item_id = i.id) mi ON TRUE
            WHERE NOT i.personal AND i.owning_org_unit_id IN (:units)
            GROUP BY i.owning_org_unit_id, i.material_id, i.game_item_id, i.location_id,
                     COALESCE(i.quality, 0), i.stolen) lots
      LEFT JOIN material m ON m.id = lots.material_id
      LEFT JOIN game_item g ON g.id = lots.game_item_id
      """;

  /** The shared lots of the given units' pools with their earmarks (REQ-XCH-042). */
  static final String ORG_LOTS =
      """
      SELECT lots.anchor AS anchor, lots.lot_key AS lotKey, lots.unit_id AS unitId,
        lots.material_id AS materialId, m.name AS materialName, m.type AS materialType,
        (m.id IS NOT NULL AND m.id_commodity IS NOT NULL) AS commodity, m.is_raw AS raw,
        m.is_mineral AS mineral, m.is_harvestable AS harvestable, m.is_refined AS refined,
        m.is_buyable AS buyable, m.is_sellable AS sellable, m.quantity_type AS quantityType,
        lots.game_item_id AS gameItemId, g.name AS gameItemName, g.grade AS gameItemGrade,
        g.wiki_type AS gameItemWikiType, g.size_class AS gameItemSize,
        g.item_class AS gameItemClass, lots.quality AS quality,
        lots.stolen AS stolen, lots.amount AS amount,
        lots.job_order_earmarked AS jobOrderEarmarked, lots.mission_earmarked AS missionEarmarked,
      """
          + PLACE
          + ORG_LOTS_SOURCE
          + PLACE_JOINS;

  /** Not instantiable. */
  private ExchangeLotSql() {}
}

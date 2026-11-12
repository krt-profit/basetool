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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Locks manufacturer rows and repoints their catalogue references during a merge. */
interface ManufacturerReassignmentRepository extends Repository<Manufacturer, UUID> {

  /**
   * Locks both manufacturers in primary key order, including against foreign key inserts.
   *
   * @param ids the survivor and retired manufacturer ids
   * @return the locked ids and versions
   */
  @Query(
      value =
          """
          SELECT id, version FROM manufacturer WHERE id IN (:ids) ORDER BY id FOR UPDATE
          """,
      nativeQuery = true)
  List<LockedRow> lockRows(@Param("ids") Collection<UUID> ids);

  /**
   * Reassigns both manufacturer roles in one versioned write per affected game item.
   *
   * @param retiredId the retired manufacturer id
   * @param survivorId the surviving manufacturer id
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE game_item SET
            manufacturer_id = CASE WHEN manufacturer_id = :retiredId
              THEN :survivorId ELSE manufacturer_id END,
            p4k_manufacturer_id = CASE WHEN p4k_manufacturer_id = :retiredId
              THEN :survivorId ELSE p4k_manufacturer_id END,
            version = version + 1,
            updated_at = CURRENT_TIMESTAMP
          WHERE manufacturer_id = :retiredId OR p4k_manufacturer_id = :retiredId
          """,
      nativeQuery = true)
  void repointItems(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Reassigns the manufacturer of each affected ship type and increments its version.
   *
   * @param retiredId the retired manufacturer id
   * @param survivorId the surviving manufacturer id
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE ship_type SET manufacturer_id = :survivorId,
            version = COALESCE(version, 0) + 1, updated_at = CURRENT_TIMESTAMP
          WHERE manufacturer_id = :retiredId
          """,
      nativeQuery = true)
  void repointShips(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /** A manufacturer id and version read while locking the row. */
  interface LockedRow {

    /**
     * Identifies the locked manufacturer.
     *
     * @return the manufacturer id
     */
    UUID getId();

    /**
     * Reads the version at lock acquisition.
     *
     * @return the current version
     */
    Long getVersion();
  }
}

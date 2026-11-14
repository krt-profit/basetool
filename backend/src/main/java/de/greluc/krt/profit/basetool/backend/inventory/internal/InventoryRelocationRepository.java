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

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The Lager queries of a patch change's relocation (REQ-PATCH-025): a row counts as from before a
 * cutoff when it was created before it or has no creation time.
 */
interface InventoryRelocationRepository extends Repository<InventoryItem, UUID> {

  /**
   * Counts a holder's rows from before a cutoff per location, pool marker and org unit.
   *
   * @param holder the holder
   * @param before the cutoff
   * @return one group per location, personal marker and org unit
   */
  @Query(
      """
      SELECT l.id AS locationId, l.name AS locationName, i.personal AS personal,
             ou.id AS orgUnitId, ou.name AS orgUnitName, COUNT(i) AS rowCount
      FROM InventoryItem i JOIN i.location l LEFT JOIN i.owningOrgUnit ou
      WHERE i.user.id = :holder AND (i.createdAt IS NULL OR i.createdAt < :before)
      GROUP BY l.id, l.name, i.personal, ou.id, ou.name
      """)
  List<LocationGroup> countByLocation(
      @Param("holder") UUID holder, @Param("before") Instant before);

  /**
   * Reads the lot facts of a holder's rows from before a cutoff that are not at a location yet.
   *
   * @param holder the holder
   * @param before the cutoff
   * @param target the location they would move to
   * @return the rows to move with their current lot facts
   */
  @Query(
      """
      SELECT i.id AS id, m.id AS materialId, g.id AS gameItemId, i.location.id AS locationId,
             i.quality AS quality, i.stolen AS stolen
      FROM InventoryItem i LEFT JOIN i.material m LEFT JOIN i.gameItem g
      WHERE i.user.id = :holder AND (i.createdAt IS NULL OR i.createdAt < :before)
        AND i.location.id <> :target
      """)
  List<MovableRow> findMovable(
      @Param("holder") UUID holder, @Param("before") Instant before, @Param("target") UUID target);

  /**
   * Moves rows to a location in place and advances their versions, so a stale form or client cannot
   * write the old location back.
   *
   * @param ids the rows, locked by the caller
   * @param location the target location
   * @param now the update time
   * @return how many rows moved
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE inventory_item
          SET location_id = :location, version = version + 1, updated_at = :now
          WHERE id IN (:ids) AND location_id <> :location
          """,
      nativeQuery = true)
  int moveTo(
      @Param("ids") Collection<UUID> ids,
      @Param("location") UUID location,
      @Param("now") Instant now);

  /** One location's, pool marker's and org unit's share of a holder's rows. */
  interface LocationGroup {

    /**
     * The location.
     *
     * @return its id
     */
    UUID getLocationId();

    /**
     * The location's curated name.
     *
     * @return the name
     */
    String getLocationName();

    /**
     * Whether the rows are the holder's personal stock.
     *
     * @return the marker
     */
    Boolean getPersonal();

    /**
     * The org unit the rows are stamped with.
     *
     * @return its id, or {@code null}
     */
    @Nullable
    UUID getOrgUnitId();

    /**
     * The org unit's name.
     *
     * @return the name, or {@code null}
     */
    @Nullable
    String getOrgUnitName();

    /**
     * How many rows.
     *
     * @return the count
     */
    long getRowCount();
  }

  /** One row to move with the facts of its current lot. */
  interface MovableRow {

    /**
     * The row.
     *
     * @return its id
     */
    UUID getId();

    /**
     * The row's material.
     *
     * @return its id, or {@code null} for an item row
     */
    @Nullable
    UUID getMaterialId();

    /**
     * The row's game item.
     *
     * @return its id, or {@code null} for a material row
     */
    @Nullable
    UUID getGameItemId();

    /**
     * The row's current location.
     *
     * @return its id
     */
    UUID getLocationId();

    /**
     * The row's quality.
     *
     * @return the quality, or {@code null}
     */
    @Nullable
    Integer getQuality();

    /**
     * Whether the row holds stolen stock.
     *
     * @return the marker
     */
    Boolean getStolen();
  }
}

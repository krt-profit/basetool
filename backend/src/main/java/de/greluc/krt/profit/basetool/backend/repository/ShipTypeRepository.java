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

package de.greluc.krt.profit.basetool.backend.repository;

import de.greluc.krt.profit.basetool.backend.model.ShipType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for Ship Type. */
@Repository
public interface ShipTypeRepository extends LookupTableRepository<ShipType, UUID> {
  /** Derived Spring-Data query - returns entities matching {@code NameIgnoreCase}. */
  Optional<ShipType> findByNameIgnoreCase(String name);

  /**
   * Resolution-chain step 1 (R2): match an inbound UEX vehicle DTO to an existing ship_type row via
   * the shared in-game asset UUID.
   *
   * @param externalUuid in-game asset UUID
   * @return matching {@link ShipType} if present
   */
  Optional<ShipType> findByExternalUuid(UUID externalUuid);

  /**
   * Finds a ship type by UEX's integer vehicle id, the fallback when the UEX payload carries no
   * UUID.
   *
   * @param uexVehicleId UEX integer vehicle id
   * @return matching {@link ShipType} if present
   */
  Optional<ShipType> findByUexVehicleId(Integer uexVehicleId);

  /**
   * Case-insensitive {@code class_name} lookup driving the P4K import's secondary resolution step
   * for ships (when the inbound ship carries no matching {@code external_uuid}). Returns a {@code
   * List} because {@code class_name} is not UNIQUE on {@code ship_type}; the caller enriches only
   * on an unambiguous single match and treats a multi-row result as ambiguous.
   *
   * @param className the RSI engine class name to match ignoring case
   * @return every ship whose {@code class_name} equals {@code className} ignoring case (possibly
   *     empty)
   */
  List<ShipType> findByClassNameIgnoreCase(String className);

  /**
   * Soft-deletes UEX-side ownership of every row whose {@code uex_vehicle_id} is set, NOT included
   * in {@code seenIds}, and whose {@code uex_deleted_at} is currently NULL. Gated by a non-empty
   * {@code seenIds} so a failed sync run does not wipe local data.
   *
   * @param seenIds UEX vehicle ids successfully processed in the current run
   * @param now timestamp to stamp on the soft-deleted rows
   * @return number of rows marked deleted
   */
  @Modifying
  @Query(
      """
      UPDATE ShipType s SET s.uexDeletedAt = :now
      WHERE s.uexVehicleId IS NOT NULL
      AND s.uexVehicleId NOT IN :seenIds
      AND s.uexDeletedAt IS NULL
      """)
  int markUexDeletedExcept(
      @Param("seenIds") Collection<Integer> seenIds, @Param("now") Instant now);

  /**
   * Soft-deletes SC Wiki ownership of every ship type the Wiki has written ({@code scwiki_synced_at
   * IS NOT NULL}) that is not in {@code seenExternalUuids} and not already marked. Callers pass a
   * non-empty seen set so a failed Wiki fetch never wipes the merge state.
   *
   * <p>Gated on {@code scwiki_synced_at} rather than {@code external_uuid}, because UEX-only
   * vehicles carry an {@code external_uuid} too.
   *
   * @param seenExternalUuids the external UUIDs the Wiki vehicle sync touched this run
   * @param now timestamp to stamp on the soft-deleted rows
   * @return number of rows marked deleted
   */
  @Modifying
  @Query(
      """
      UPDATE ShipType s SET s.scwikiDeletedAt = :now
      WHERE s.scwikiSyncedAt IS NOT NULL
      AND s.externalUuid NOT IN :seenExternalUuids
      AND s.scwikiDeletedAt IS NULL
      """)
  int markScwikiDeletedExcept(
      @Param("seenExternalUuids") Collection<UUID> seenExternalUuids, @Param("now") Instant now);

  /**
   * Counts the live Wiki-written ship types ({@code scwiki_synced_at IS NOT NULL}, not tombstoned),
   * using the same gate as {@link #markScwikiDeletedExcept}. Reported by the vehicle sync on a
   * {@code 304 Not Modified} so a cached run is not read as a zero-item outage.
   *
   * @return the number of non-tombstoned ship types the SC Wiki sync has written
   */
  @Query(
      """
      SELECT COUNT(s) FROM ShipType s
      WHERE s.scwikiSyncedAt IS NOT NULL
      AND s.scwikiDeletedAt IS NULL
      """)
  long countLiveScwikiShipTypes();

  /**
   * Derived Spring-Data check - returns {@code true} iff at least one row matches {@code
   * ManufacturerId}.
   */
  boolean existsByManufacturerId(UUID manufacturerId);

  /** Derived Spring-Data query - returns entities matching {@code HiddenFalse}. */
  Page<ShipType> findByHiddenFalse(Pageable pageable);

  /**
   * The UEX vehicle id and local id of every ship type that carries one, in one query — the item
   * sync resolves each vehicle-bound item's ship type against the resulting map (BE-PERF-09).
   *
   * @return one (UEX vehicle id, ship type id) row per ship type with a UEX vehicle id
   */
  @Query(
      "SELECT s.uexVehicleId AS uexId, s.id AS id FROM ShipType s WHERE s.uexVehicleId IS NOT NULL")
  List<UexKeyRef> findUexVehicleRefs();
}

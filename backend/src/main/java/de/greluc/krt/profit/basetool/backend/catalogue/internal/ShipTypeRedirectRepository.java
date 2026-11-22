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

import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Stores the aliases a ship type merge leaves behind (REQ-DATA-042). */
public interface ShipTypeRedirectRepository extends JpaRepository<ShipTypeRedirect, UUID> {

  /**
   * Captures both ship type rows as JSON before the merge changes either.
   *
   * @param retiredId the ship type merged away
   * @param survivorId the ship type that stays
   * @return the JSON of both rows
   */
  @Query(
      value =
          """
          SELECT jsonb_build_object('retiredShipType', to_jsonb(old_type),
                                    'survivingShipType', to_jsonb(new_type))::text
          FROM ship_type old_type JOIN ship_type new_type ON new_type.id = :survivorId
          WHERE old_type.id = :retiredId
          """,
      nativeQuery = true)
  String snapshot(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Keeps the id of a merged ship type as an alias of the one that stays. Written as a statement,
   * so no managed alias can go stale when a later merge repoints it.
   *
   * @param retiredId the ship type merged away
   * @param survivorId the ship type that stays
   * @param snapshot both rows as JSON, from {@link #snapshot}
   * @param uexVehicleId the merged ship type's UEX vehicle id the survivor did not take, or {@code
   *     null}
   * @return the number of aliases written
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO ship_type_redirect (id, canonical_id, retired_snapshot, uex_vehicle_id)
          VALUES (:retiredId, :survivorId, CAST(:snapshot AS jsonb), :uexVehicleId)
          """,
      nativeQuery = true)
  int alias(
      @Param("retiredId") UUID retiredId,
      @Param("survivorId") UUID survivorId,
      @Param("snapshot") String snapshot,
      @Param("uexVehicleId") @Nullable Integer uexVehicleId);

  /**
   * Points the aliases of a ship type being merged away at the one that stays, so every alias
   * resolves in one hop.
   *
   * @param retiredId the ship type merged away
   * @param survivorId the ship type that stays
   * @return the number of aliases moved
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE ship_type_redirect SET canonical_id = :survivorId,
            version = version + 1, updated_at = CURRENT_TIMESTAMP
          WHERE canonical_id = :retiredId
          """,
      nativeQuery = true)
  int repoint(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Tells whether a merge into a ship type left a UEX vehicle id behind (REQ-DATA-043).
   *
   * @param canonicalShipTypeId the ship type that stayed
   * @param uexVehicleId the UEX vehicle id
   * @return whether a redirect into the ship type keeps that id as left behind
   */
  boolean existsByCanonicalShipTypeIdAndUexVehicleId(
      UUID canonicalShipTypeId, Integer uexVehicleId);

  /**
   * Hands the left-behind UEX vehicle id of the merges into a ship type over when the ship type
   * takes that listing: the redirects then keep the listing the ship type gives up (REQ-DATA-043).
   *
   * @param survivorId the ship type that stayed
   * @param taken the left-behind UEX vehicle id the ship type takes
   * @param released the UEX vehicle id the ship type gives up, or {@code null} when it held none
   * @return the number of redirects changed
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE ship_type_redirect SET uex_vehicle_id = :released,
            version = version + 1, updated_at = CURRENT_TIMESTAMP
          WHERE canonical_id = :survivorId AND uex_vehicle_id = :taken
          """,
      nativeQuery = true)
  int handOverUexVehicleId(
      @Param("survivorId") UUID survivorId,
      @Param("taken") int taken,
      @Param("released") @Nullable Integer released);
}

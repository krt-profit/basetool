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

package de.greluc.krt.profit.basetool.backend.hangar.internal;

import de.greluc.krt.profit.basetool.backend.hangar.api.Ship;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * The hangar queries of a patch change's relocation and its lock (REQ-PATCH-023, REQ-PATCH-025): a
 * ship counts as from before a cutoff when it was created before it or has no creation time.
 */
interface HangarRelocationRepository extends Repository<Ship, UUID> {

  /**
   * Counts an owner's ships from before a cutoff per location.
   *
   * @param owner the owner
   * @param before the cutoff
   * @return one group per location, ships without one under a {@code null} location
   */
  @Query(
      """
      SELECT l.id AS locationId, l.name AS locationName, COUNT(s) AS shipCount
      FROM Ship s LEFT JOIN s.location l
      WHERE s.owner.id = :owner AND (s.createdAt IS NULL OR s.createdAt < :before)
      GROUP BY l.id, l.name
      """)
  List<LocationGroup> countByLocation(@Param("owner") UUID owner, @Param("before") Instant before);

  /**
   * Returns the owners of those of the given ships that were created before a cutoff.
   *
   * @param ids the ships
   * @param before the cutoff
   * @return the distinct owners
   */
  @Query(
      """
      SELECT DISTINCT s.owner.id FROM Ship s
      WHERE s.id IN :ids AND (s.createdAt IS NULL OR s.createdAt < :before)
      """)
  List<UUID> findOwnersOfShipsBefore(
      @Param("ids") Collection<UUID> ids, @Param("before") Instant before);

  /** One location's share of an owner's ships. */
  interface LocationGroup {

    /**
     * The location.
     *
     * @return its id, or {@code null} for ships without one
     */
    @Nullable
    UUID getLocationId();

    /**
     * The location's curated name.
     *
     * @return the name, or {@code null} for ships without one
     */
    @Nullable
    String getLocationName();

    /**
     * How many ships.
     *
     * @return the count
     */
    long getShipCount();
  }
}

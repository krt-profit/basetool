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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import de.greluc.krt.profit.basetool.backend.mission.api.MissionUnit;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository for Mission Unit. */
public interface MissionUnitRepository extends JpaRepository<MissionUnit, UUID> {
  /** Derived Spring-Data query - returns entities matching {@code ShipId}. */
  List<MissionUnit> findByShipId(UUID shipId);

  /**
   * Reads every mission unit that carries a ship of the given owner, as a projection that loads no
   * unit, mission, ship or member into the persistence context.
   *
   * @param ownerId the ships' owner
   * @return one release per unit, ordered by mission and unit; empty when no unit carries one
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.mission.internal.UnitShipRelease(
        u.id, u.name, m.id, m.name, m.status, r.id, s.id, st.name)
      FROM MissionUnit u
      JOIN u.mission m
      JOIN u.ship s
      JOIN s.shipType st
      LEFT JOIN u.responsibleUser r
      WHERE s.owner.id = :ownerId
      ORDER BY m.id, u.id
      """)
  List<UnitShipRelease> findShipReleasesByShipOwnerId(@Param("ownerId") UUID ownerId);

  /**
   * Clears the ship on every mission unit that carries a ship of the given owner in one statement,
   * bumping each touched row's {@code version} so an open edit form holding the old one is refused.
   *
   * <p>Keeps the persistence context: clearing it would detach the member the account deletion is
   * about to remove.
   *
   * @param ownerId the ships' owner
   * @param now the instant written to each released unit's {@code updatedAt}
   * @return the number of units released
   */
  @Modifying
  @Query(
      """
      UPDATE MissionUnit u SET u.ship = NULL, u.version = u.version + 1, u.updatedAt = :now
      WHERE u.ship.id IN (SELECT s.id FROM Ship s WHERE s.owner.id = :ownerId)
      """)
  int releaseShipsOfOwner(@Param("ownerId") UUID ownerId, @Param("now") Instant now);
}

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

import java.util.Collection;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for the ship types' loadout port trees (REQ-P4K-040). */
@Repository
public interface ShipTypeLoadoutPortRepository extends JpaRepository<ShipTypeLoadoutPort, UUID> {

  /**
   * Deletes the whole port tree of a ship type; a bulk delete, so it runs before the new tree is
   * inserted.
   *
   * @param shipTypeId the ship type
   * @return the ports deleted
   */
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM ShipTypeLoadoutPort p WHERE p.shipTypeId = :shipTypeId")
  int deleteTree(@Param("shipTypeId") UUID shipTypeId);

  /**
   * Deletes the port trees of every ship type a complete loadouts kind no longer lists.
   *
   * @param shipTypeIds the ship types whose trees the run wrote; never empty
   * @return the ports deleted
   */
  @Modifying(flushAutomatically = true)
  @Query("DELETE FROM ShipTypeLoadoutPort p WHERE p.shipTypeId NOT IN :shipTypeIds")
  int deleteTreesExcept(@Param("shipTypeIds") Collection<UUID> shipTypeIds);
}

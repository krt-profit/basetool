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

import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Reads the member's rows the stock picker of a pledge offers (REQ-SUP-025). */
public interface InventoryDonationSourceRepository extends Repository<InventoryItem, UUID> {

  /**
   * Loads the member's own rows of one material or game item that are not marked stolen, with their
   * location and donation slices.
   *
   * @param userId the member
   * @param materialId the material, or {@code null} for a game item
   * @param gameItemId the game item, or {@code null} for a material
   * @return the rows, best quality first, then by location name
   */
  @EntityGraph(attributePaths = {"material", "gameItem", "location", "donationAllocations"})
  @Query(
      """
      SELECT i FROM InventoryItem i
      WHERE i.user.id = :userId AND (i.stolen IS NULL OR i.stolen = FALSE)
        AND ((:materialId IS NOT NULL AND i.material.id = :materialId)
          OR (:gameItemId IS NOT NULL AND i.gameItem.id = :gameItemId))
      ORDER BY i.quality DESC NULLS LAST, i.location.name ASC, i.id ASC
      """)
  List<InventoryItem> findDonatableForUser(
      @Param("userId") UUID userId,
      @Param("materialId") @Nullable UUID materialId,
      @Param("gameItemId") @Nullable UUID gameItemId);
}

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

import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.projection.JobOrderItemBlueprintDrift;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

/** Repository for the ordered finished-item lines of {@code ITEM} job orders. */
@Repository
public interface JobOrderItemRepository extends JpaRepository<JobOrderItem, UUID> {

  /**
   * Finds every ordered-item line whose chosen blueprint no longer produces the line's game item
   * (REQ-ORDERS-033). The pairing is validated when the line is written, but {@code
   * ScWikiBlueprintSyncService} re-resolves a blueprint's output item from the Wiki feed on every
   * run, so an upstream re-point silently leaves existing lines snapshotting a foreign recipe. This
   * is the detection query behind the scheduled integrity sweep.
   *
   * <p>The {@code LEFT JOIN} on the output item is deliberate: a blueprint whose output UUID
   * stopped resolving to any known game item is drift too, not a row to skip.
   *
   * @return one row per drifted line (empty when every line is consistent), ordered oldest-order
   *     first so the operator log reads chronologically
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.projection.JobOrderItemBlueprintDrift(
          i.id, o.id, o.displayId, gi.name, b.outputName, b.scwikiKey)
      FROM JobOrderItem i
      JOIN i.jobOrder o
      JOIN i.gameItem gi
      JOIN i.blueprint b
      LEFT JOIN b.outputItem oi
      WHERE oi IS NULL OR oi.id <> gi.id
      ORDER BY o.displayId, gi.name
      """)
  List<JobOrderItemBlueprintDrift> findBlueprintOutputDrift();
}

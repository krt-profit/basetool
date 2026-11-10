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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemPlan;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/** Bounded owning reads of kept ITEM snapshots before preparing an order edit. */
public interface JobOrderItemPlanReads extends Repository<JobOrderItem, UUID> {
  /**
   * Initializes every kept material collection through a single fetched bag.
   *
   * @param ids the persisted item-line identities
   * @return the managed lines with material snapshots initialized
   */
  @Query(
      """
      SELECT DISTINCT i FROM JobOrderItem i LEFT JOIN FETCH i.materials WHERE i.id IN :ids
      """)
  List<JobOrderItem> initializeMaterials(@Param("ids") Collection<UUID> ids);

  /**
   * Reads current scalar recipe, progress and version facts without reusing managed line state.
   *
   * @param ids the persisted item-line identities
   * @return current item-line facts in identity order
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemPlan$KeptLine(
        i.id, i.version, i.amount, i.manufacturedAmount, i.deliveredAmount,
        i.gameItem.id, i.blueprint.id, p.id)
      FROM JobOrderItem i LEFT JOIN i.parentItem p WHERE i.id IN :ids ORDER BY i.id
      """)
  List<JobOrderItemPlan.KeptLine> findLineFacts(@Param("ids") Collection<UUID> ids);

  /**
   * Reads current material and quality identities without reusing managed requirement state.
   *
   * @param ids the persisted item-line identities
   * @return current requirement facts in owning-line and requirement identity order
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderItemPlan$KeptMaterial(
        m.id, m.jobOrderItem.id, m.material.id, m.qualityTier.id, m.requiredQuantity, m.version)
      FROM JobOrderItemMaterial m WHERE m.jobOrderItem.id IN :ids ORDER BY m.jobOrderItem.id, m.id
      """)
  List<JobOrderItemPlan.KeptMaterial> findMaterialFacts(@Param("ids") Collection<UUID> ids);
}

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

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Reads and writes the minimum stock levels and reads the pool stock they count (REQ-INV-067). */
public interface StockLevelRepository extends JpaRepository<StockLevel, UUID> {

  /**
   * Lists the levels of the active units in the caller's Lager scope, catalogue entries loaded.
   *
   * @param isAdminAllScope whether the caller is an admin without a pinned unit
   * @param activeOrgUnitId the pinned unit, or {@code null}
   * @param memberOrgUnitIds the caller's member units when nothing is pinned
   * @param orgUnitId only this unit, or {@code null} for every unit in scope
   * @return the levels, by unit name, material name and tier floor descending
   */
  @Query(
      """
      SELECT l FROM StockLevel l
      JOIN FETCH l.orgUnit u
      JOIN FETCH l.material m
      LEFT JOIN FETCH l.qualityTier t
      WHERE u.active = true
        AND (:orgUnitId IS NULL OR u.id = :orgUnitId)
        AND (
          :isAdminAllScope = true
          OR (:activeOrgUnitId IS NOT NULL AND u.id = :activeOrgUnitId)
          OR (:activeOrgUnitId IS NULL AND u.id IN :memberOrgUnitIds)
        )
      ORDER BY u.name, m.name, t.minQuality DESC NULLS LAST
      """)
  List<StockLevel> findVisible(
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") @Nullable UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      @Param("orgUnitId") @Nullable UUID orgUnitId);

  /**
   * Lists every level of one material in one unit, for the allocation of a single changed level.
   *
   * @param orgUnitId the unit
   * @param materialId the material
   * @return the levels, tiers loaded
   */
  @Query(
      """
      SELECT l FROM StockLevel l LEFT JOIN FETCH l.qualityTier
      JOIN FETCH l.material JOIN FETCH l.orgUnit
      WHERE l.orgUnit.id = :orgUnitId AND l.material.id = :materialId
      """)
  List<StockLevel> findByUnitAndMaterial(
      @Param("orgUnitId") UUID orgUnitId, @Param("materialId") UUID materialId);

  /**
   * Finds the level of a unit, material and tier, the untiered one for a {@code null} tier.
   *
   * @param orgUnitId the unit
   * @param materialId the material
   * @param qualityTierId the tier, or {@code null}
   * @return the level, or empty
   */
  @Query(
      """
      SELECT l FROM StockLevel l LEFT JOIN l.qualityTier t
      WHERE l.orgUnit.id = :orgUnitId AND l.material.id = :materialId
        AND ((:qualityTierId IS NULL AND t IS NULL) OR t.id = :qualityTierId)
      """)
  Optional<StockLevel> findByKey(
      @Param("orgUnitId") UUID orgUnitId,
      @Param("materialId") UUID materialId,
      @Param("qualityTierId") @Nullable UUID qualityTierId);

  /**
   * Sums the pool stock of the given units and materials by quality, personal and stolen marker,
   * with the job-order, mission and donation earmarks on it, over every location.
   *
   * @param units the units; never empty
   * @param materials the materials; never empty
   * @return one row per unit, material, quality and markers
   */
  @Query(
      value =
          """
          SELECT i.owning_org_unit_id AS unitId, i.material_id AS materialId,
                 COALESCE(i.quality, 0) AS quality, i.personal AS personal, i.stolen AS stolen,
                 SUM(i.amount) AS amount,
                 SUM(COALESCE(jo.amount, 0) + COALESCE(mi.amount, 0) + COALESCE(dn.amount, 0))
                     AS earmarked
          FROM inventory_item i
          LEFT JOIN LATERAL (SELECT SUM(a.amount) AS amount
                             FROM inventory_item_job_order_allocation a
                             WHERE a.inventory_item_id = i.id) jo ON TRUE
          LEFT JOIN LATERAL (SELECT SUM(a.amount) AS amount
                             FROM inventory_item_mission_allocation a
                             WHERE a.inventory_item_id = i.id) mi ON TRUE
          LEFT JOIN LATERAL (SELECT SUM(a.amount) AS amount
                             FROM inventory_item_donation_allocation a
                             WHERE a.inventory_item_id = i.id) dn ON TRUE
          WHERE i.owning_org_unit_id IN (:units) AND i.material_id IN (:materials)
          GROUP BY i.owning_org_unit_id, i.material_id, COALESCE(i.quality, 0), i.personal,
                   i.stolen
          """,
      nativeQuery = true)
  List<PoolStockRow> sumPoolStock(
      @Param("units") Collection<UUID> units, @Param("materials") Collection<UUID> materials);

  /** One summed slice of a unit's pool stock of a material. */
  interface PoolStockRow {

    /**
     * The owning unit.
     *
     * @return the unit id
     */
    UUID getUnitId();

    /**
     * The material.
     *
     * @return the material id
     */
    UUID getMaterialId();

    /**
     * The stock quality, {@code 0} for rows without one.
     *
     * @return the quality
     */
    int getQuality();

    /**
     * Whether the rows are personal.
     *
     * @return the personal marker
     */
    boolean getPersonal();

    /**
     * Whether the rows are marked „gestohlen".
     *
     * @return the stolen marker
     */
    boolean getStolen();

    /**
     * The summed amount.
     *
     * @return the amount
     */
    double getAmount();

    /**
     * The summed earmarks.
     *
     * @return the earmarked amount
     */
    double getEarmarked();
  }
}

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
   * Levels of a catalogue entry that the catalogue no longer lists stay stored but are left out.
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
      LEFT JOIN FETCH l.material m
      LEFT JOIN FETCH l.gameItem g
      LEFT JOIN FETCH l.qualityTier t
      WHERE u.active = true
        AND m.scwikiDeletedAt IS NULL
        AND (
          (g.scwikiDeletedAt IS NULL AND g.uexDeletedAt IS NULL)
          OR (g.scwikiSyncedAt IS NOT NULL AND g.scwikiDeletedAt IS NULL)
          OR (g.uexItemId IS NOT NULL AND g.uexDeletedAt IS NULL)
        )
        AND (:orgUnitId IS NULL OR u.id = :orgUnitId)
        AND (
          :isAdminAllScope = true
          OR (:activeOrgUnitId IS NOT NULL AND u.id = :activeOrgUnitId)
          OR (:activeOrgUnitId IS NULL AND u.id IN :memberOrgUnitIds)
        )
      ORDER BY u.name, COALESCE(m.name, g.name), t.minQuality DESC NULLS LAST
      """)
  List<StockLevel> findVisible(
      @Param("isAdminAllScope") boolean isAdminAllScope,
      @Param("activeOrgUnitId") @Nullable UUID activeOrgUnitId,
      @Param("memberOrgUnitIds") Collection<UUID> memberOrgUnitIds,
      @Param("orgUnitId") @Nullable UUID orgUnitId);

  /**
   * Lists every level of one catalogue entry in one unit, for the allocation of a single changed
   * level.
   *
   * @param orgUnitId the unit
   * @param subjectId the material or the game item
   * @return the levels, tiers loaded
   */
  @Query(
      """
      SELECT l FROM StockLevel l LEFT JOIN FETCH l.qualityTier
      LEFT JOIN FETCH l.material m LEFT JOIN FETCH l.gameItem g JOIN FETCH l.orgUnit
      WHERE l.orgUnit.id = :orgUnitId AND (m.id = :subjectId OR g.id = :subjectId)
      """)
  List<StockLevel> findByUnitAndSubject(
      @Param("orgUnitId") UUID orgUnitId, @Param("subjectId") UUID subjectId);

  /**
   * Lists every level of a material, units and tiers loaded, for the removal with the material.
   *
   * @param materialId the material
   * @return the material's levels in every unit
   */
  @Query(
      """
      SELECT l FROM StockLevel l JOIN FETCH l.orgUnit JOIN FETCH l.material
      LEFT JOIN FETCH l.qualityTier
      WHERE l.material.id = :materialId
      """)
  List<StockLevel> findByMaterial(@Param("materialId") UUID materialId);

  /**
   * Finds the level of a unit and game item.
   *
   * @param orgUnitId the unit
   * @param gameItemId the game item
   * @return the level, or empty
   */
  @Query(
      """
      SELECT l FROM StockLevel l
      WHERE l.orgUnit.id = :orgUnitId AND l.gameItem.id = :gameItemId
      """)
  Optional<StockLevel> findByItemKey(
      @Param("orgUnitId") UUID orgUnitId, @Param("gameItemId") UUID gameItemId);

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
   * Sums the pool stock of the given units and catalogue entries by quality, personal and stolen
   * marker, with the job-order, mission and donation earmarks on it, over every location.
   *
   * @param units the units; never empty
   * @param subjects the materials and game items; never empty
   * @return one row per unit, catalogue entry, quality and markers
   */
  @Query(
      value =
          """
          SELECT i.owning_org_unit_id AS unitId,
                 COALESCE(i.material_id, i.game_item_id) AS subjectId,
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
          WHERE i.owning_org_unit_id IN (:units)
            AND COALESCE(i.material_id, i.game_item_id) IN (:subjects)
          GROUP BY i.owning_org_unit_id, COALESCE(i.material_id, i.game_item_id),
                   COALESCE(i.quality, 0), i.personal, i.stolen
          """,
      nativeQuery = true)
  List<PoolStockRow> sumPoolStock(
      @Param("units") Collection<UUID> units, @Param("subjects") Collection<UUID> subjects);

  /** One summed slice of a unit's pool stock of a material or game item. */
  interface PoolStockRow {

    /**
     * The owning unit.
     *
     * @return the unit id
     */
    UUID getUnitId();

    /**
     * The material or game item.
     *
     * @return the catalogue entry id
     */
    UUID getSubjectId();

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

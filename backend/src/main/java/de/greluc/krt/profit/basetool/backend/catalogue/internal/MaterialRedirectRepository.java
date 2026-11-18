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

import de.greluc.krt.profit.basetool.backend.catalogue.api.UexKeyRef;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Stores the aliases a material merge leaves behind (REQ-DATA-046). */
public interface MaterialRedirectRepository extends JpaRepository<MaterialRedirect, UUID> {

  /**
   * Captures both materials and their terminal prices as JSON before the merge changes either.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the JSON of both rows and their prices
   */
  @Query(
      value =
          """
          SELECT jsonb_build_object(
            'retiredMaterial', to_jsonb(old_material), 'survivingMaterial', to_jsonb(new_material),
            'retiredPrices', (SELECT COALESCE(jsonb_agg(to_jsonb(p)), '[]'::jsonb)
                              FROM material_price p WHERE p.material_id = :retiredId),
            'survivingPrices', (SELECT COALESCE(jsonb_agg(to_jsonb(p)), '[]'::jsonb)
                                FROM material_price p WHERE p.material_id = :survivorId)
          )::text FROM material old_material JOIN material new_material ON new_material.id = :survivorId
          WHERE old_material.id = :retiredId
          """,
      nativeQuery = true)
  String snapshot(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Keeps the id of a merged material as an alias of the one that stays. Written as a statement, so
   * no managed alias can go stale when a later merge repoints it.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @param snapshot both rows as JSON, from {@link #snapshot}
   * @param uexCommodityId the merged material's UEX commodity id, or {@code null}
   * @return the number of aliases written
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          INSERT INTO material_redirect (id, canonical_id, retired_snapshot, uex_commodity_id)
          VALUES (:retiredId, :survivorId, CAST(:snapshot AS jsonb), :uexCommodityId)
          """,
      nativeQuery = true)
  int alias(
      @Param("retiredId") UUID retiredId,
      @Param("survivorId") UUID survivorId,
      @Param("snapshot") String snapshot,
      @Param("uexCommodityId") @Nullable Integer uexCommodityId);

  /**
   * Points the aliases of a material being merged away at the one that stays, so every alias
   * resolves in one hop.
   *
   * @param retiredId the material merged away
   * @param survivorId the material that stays
   * @return the number of aliases moved
   */
  @Modifying(flushAutomatically = true)
  @Query(
      value =
          """
          UPDATE material_redirect SET canonical_id = :survivorId,
            version = version + 1, updated_at = CURRENT_TIMESTAMP
          WHERE canonical_id = :retiredId
          """,
      nativeQuery = true)
  int repoint(@Param("retiredId") UUID retiredId, @Param("survivorId") UUID survivorId);

  /**
   * Tells whether a UEX commodity id is the listing of a material a merge retired.
   *
   * @param uexCommodityId the UEX commodity id
   * @return whether an alias keeps it
   */
  @Query("SELECT COUNT(r) > 0 FROM MaterialRedirect r WHERE r.uexCommodityId = :uexCommodityId")
  boolean isMergedUexCommodityId(@Param("uexCommodityId") int uexCommodityId);

  /**
   * Lists every merged UEX commodity listing with the material it was merged into, for the
   * commodity-price sync.
   *
   * @return one (UEX commodity id, surviving material id) row per merged listing
   */
  @NotNull
  @Query(
      "SELECT r.uexCommodityId AS uexId, r.canonicalMaterial.id AS id FROM MaterialRedirect r"
          + " WHERE r.uexCommodityId IS NOT NULL")
  List<UexKeyRef> findMergedUexCommodityRefs();
}

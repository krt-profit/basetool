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

import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialPriceOverviewDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialReferenceDto;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Spring Data repository for Material. */
@Repository
public interface MaterialRepository extends JpaRepository<Material, UUID> {

  /**
   * Returns slim {@code MaterialReferenceDto}s (id, name, quantity type) for every visible
   * material, ordered by name, for material pickers.
   *
   * <p>The list is complete and unbounded; bounded pickers use {@link #searchPicker(String,
   * boolean, MaterialType, boolean, Pageable)}.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.dto.MaterialReferenceDto(m.id,
      m.name, m.quantityType) FROM Material m WHERE m.isVisible = true ORDER BY m.name
      """)
  List<MaterialReferenceDto> findAllReference();

  /**
   * Paged live search for the material pickers (REQ-FE-016): visible materials whose name contains
   * the fragment, case-insensitively, with the refined material fetch-joined.
   *
   * @param q the LIKE-escaped name fragment, or {@code null} for no filter
   * @param jobOrderOnly when true, only {@code isJobOrder = true} materials
   * @param rawType the {@link MaterialType#RAW} constant, bound as a parameter
   * @param rawOnly when true, only refinery inputs ({@code type = rawType} or manually raw-flagged)
   * @param pageable page request (sorted by the whitelisted picker sort, typically name ascending)
   * @return one page of matching visible materials with the refined material initialized
   */
  @Query(
      value =
          """
          SELECT m FROM Material m LEFT JOIN FETCH m.refinedMaterial WHERE m.isVisible = true
          AND (cast(:q as string) IS NULL
              OR LOWER(m.name) LIKE LOWER(CONCAT('%', cast(:q as string), '%')))
          AND (:jobOrderOnly = false OR m.isJobOrder = true)
          AND (:rawOnly = false OR m.type = :rawType OR m.isManualRawMaterial = true)
          """,
      countQuery =
          """
          SELECT COUNT(m) FROM Material m WHERE m.isVisible = true
          AND (cast(:q as string) IS NULL
              OR LOWER(m.name) LIKE LOWER(CONCAT('%', cast(:q as string), '%')))
          AND (:jobOrderOnly = false OR m.isJobOrder = true)
          AND (:rawOnly = false OR m.type = :rawType OR m.isManualRawMaterial = true)
          """)
  Page<Material> searchPicker(
      @Param("q") String q,
      @Param("jobOrderOnly") boolean jobOrderOnly,
      @Param("rawType") MaterialType rawType,
      @Param("rawOnly") boolean rawOnly,
      Pageable pageable);

  /**
   * Paged list of visible materials for the public trading catalog; the admin catalog uses {@link
   * #findAll(Pageable)} instead.
   *
   * @param pageable page request
   * @return paged visible materials
   */
  Page<Material> findByIsVisibleTrue(Pageable pageable);

  /**
   * Returns every <b>visible</b> material flagged {@code isJobOrder=true}, ordered by name. The
   * {@code isVisible} clause keeps unreviewed wiki-only rows out of the job-order material picker.
   */
  List<Material> findAllByIsJobOrderTrueAndIsVisibleTrueOrderByNameAsc();

  /** Derived Spring-Data query - returns entities matching {@code IdCommodity}. */
  Optional<Material> findByIdCommodity(Integer idCommodity);

  /** Derived Spring-Data query - returns entities matching {@code Name}. */
  Optional<Material> findByName(String name);

  /**
   * Finds a material by name, ignoring case; at most one row matches.
   *
   * @param name the material name to match ignoring case
   * @return the material if one exists with that name (any case)
   */
  Optional<Material> findByNameIgnoreCase(String name);

  /**
   * Batched case-insensitive name lookup: returns every material whose lower-cased name is in
   * {@code lowerNames}.
   *
   * <p>Callers must lower-case the names with {@code Locale.ROOT}; matching is reliable for ASCII
   * names only.
   *
   * @param lowerNames the already-{@code Locale.ROOT}-lower-cased material names to match
   * @return the materials whose lower-cased name is in the set; empty when none match or {@code
   *     lowerNames} is empty
   */
  @Query("SELECT m FROM Material m WHERE LOWER(m.name) IN :lowerNames")
  List<Material> findByNameInIgnoreCase(@Param("lowerNames") Collection<String> lowerNames);

  /**
   * Finds the material linked to an SC Wiki commodity by a previous sync.
   *
   * @param scwikiUuid the SC Wiki commodity UUID
   * @return the material if a previous sync linked it
   */
  Optional<Material> findByScwikiUuid(UUID scwikiUuid);

  /**
   * Marks as SC-Wiki-deleted every material whose {@code scwiki_uuid} is set, not in {@code
   * seenScwikiUuids}, and not already marked.
   *
   * <p>Callers must not invoke it with an empty seen set.
   *
   * @param seenScwikiUuids the Wiki UUIDs successfully processed in the current run
   * @param now timestamp to stamp on the soft-deleted rows
   * @return number of rows marked deleted
   */
  @Modifying
  @Query(
      """
      UPDATE Material m SET m.scwikiDeletedAt = :now
      WHERE m.scwikiUuid IS NOT NULL
      AND m.scwikiUuid NOT IN :seenScwikiUuids
      AND m.scwikiDeletedAt IS NULL
      """)
  int markScwikiDeleted(
      @Param("seenScwikiUuids") Collection<UUID> seenScwikiUuids, @Param("now") Instant now);

  /**
   * Counts the materials that carry an SC Wiki UUID and are not tombstoned; reported by the
   * commodity sync when the Wiki catalogue answers {@code 304 Not Modified}.
   *
   * @return the number of non-tombstoned materials carrying a SC Wiki UUID
   */
  @Query(
      """
      SELECT COUNT(m) FROM Material m
      WHERE m.scwikiUuid IS NOT NULL
      AND m.scwikiDeletedAt IS NULL
      """)
  long countLiveScwikiMaterials();

  /**
   * Returns the visible materials the refinery-order create path accepts as input ({@code type ==
   * RAW} or {@code isManualRawMaterial}), the candidate set for the screenshot import.
   *
   * @param rawType always {@link de.greluc.krt.profit.basetool.backend.model.MaterialType#RAW},
   *     bound as a parameter
   * @return visible refinery-input candidates, ordered by name
   */
  @Query(
      """
      SELECT m FROM Material m WHERE m.isVisible = true
      AND (m.type = :rawType OR m.isManualRawMaterial = true) ORDER BY m.name
      """)
  List<Material> findRefineryInputCandidates(@Param("rawType") MaterialType rawType);

  /**
   * Returns only the materials that actually have at least one price row at a non-hidden terminal -
   * useful to suppress materials with no buy/sell data in the trade UI.
   */
  @Query(
      """
      SELECT m FROM Material m WHERE EXISTS (SELECT 1 FROM MaterialPrice p WHERE p.material = m
      AND (p.terminal.hidden = false OR p.terminal.hidden IS NULL))
      """)
  Page<Material> findAllWithPrices(Pageable pageable);

  /**
   * Per-material price summary used by the price-overview view: best (minimum) positive buy price
   * and best (maximum) positive sell price across every non-hidden terminal, plus the material's
   * category and UEX-style flag columns flattened into the DTO. {@code name} is an optional
   * case-insensitive substring filter.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.dto.MaterialPriceOverviewDto(
          m.id, m.name, c.id, c.name, c.version,
          m.isIllegal, m.isVolatileQt, m.isVolatileTime,
          MIN(CASE WHEN p.priceBuy > 0 THEN p.priceBuy ELSE null END),
          MAX(CASE WHEN p.priceSell > 0 THEN p.priceSell ELSE null END)
      )
      FROM Material m
      LEFT JOIN m.category c
      JOIN MaterialPrice p ON p.material = m
      WHERE (cast(:name as string) IS NULL
          OR LOWER(m.name) LIKE LOWER(CONCAT('%', cast(:name as string), '%')))
      AND (p.terminal.hidden = false OR p.terminal.hidden IS NULL)
      GROUP BY m.id, m.name, c.id, c.name, c.version,
          m.isIllegal, m.isVolatileQt, m.isVolatileTime
      """)
  Page<MaterialPriceOverviewDto> getMaterialPriceOverview(
      @Param("name") String name, Pageable pageable);

  /**
   * The UEX commodity id and local id of every material. One query for the whole table; the UEX
   * syncs resolve every row of a run against the resulting map instead of looking the parent up per
   * row (BE-PERF-09).
   *
   * @return one (UEX id, local id) row per Material that carries a UEX id
   */
  @Query(
      "SELECT e.idCommodity AS uexId, e.id AS id FROM Material e WHERE e.idCommodity IS NOT NULL")
  List<UexKeyRef> findUexCommodityRefs();
}

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
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialPriceOverviewDto;
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
   * Returns slim {@code MaterialReferenceDto}s (id, name, quantity-type) for every <b>visible</b>
   * material, ordered by name. Used to populate material pickers (inventory, alias targets) without
   * pulling the full Material aggregate. Wiki-only commodities imported {@code is_visible = false}
   * (§4.3) are excluded so unreviewed entries never appear in a picker — see {@code isVisible} on
   * {@link Material}.
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.dto.MaterialReferenceDto(m.id,
      m.name, m.quantityType) FROM Material m WHERE m.isVisible = true ORDER BY m.name
      """)
  List<de.greluc.krt.profit.basetool.backend.model.dto.MaterialReferenceDto> findAllReference();

  /**
   * Paged list of materials with {@code is_visible = true}, used for the public/trading catalog
   * list. Wiki-only commodities inserted invisible (§4.3) are filtered out here; the admin catalog
   * uses the unfiltered {@link #findAll(Pageable)} instead so it can review and unhide them.
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
   * Case-insensitive name lookup. Used to bridge a SC-Wiki blueprint ITEM ingredient (which the
   * wiki counts in pieces and resolves to a {@code game_item}) to the shared {@code material}
   * catalogue by name, so a non-craftable component that also exists as a material is treated as a
   * procurement requirement rather than a (recipe-less) sub-assembly. {@code material.name} is
   * unique, so at most one row matches.
   *
   * @param name the material name to match ignoring case
   * @return the material if one exists with that name (any case)
   */
  Optional<Material> findByNameIgnoreCase(String name);

  /**
   * Batched case-insensitive name lookup: returns every material whose lower-cased name is in
   * {@code lowerNames} (the caller lower-cases each name first). The bulk counterpart of {@link
   * #findByNameIgnoreCase(String)}, used by the blueprint craftability calculation (#781) to bridge
   * a recipe's PIECE-counted ITEM ingredients to their {@code material} rows across the caller's
   * whole owned set in one query rather than one lookup per ingredient. {@code material.name} is
   * unique, so at most one row matches each distinct lower-cased name.
   *
   * <p>Unlike the derived {@link #findByNameIgnoreCase(String)} — which folds <em>both</em>
   * operands with the database {@code LOWER()} — this folds the candidate names caller-side (JVM)
   * and the column DB-side. The two folds are byte-identical for ASCII, which is all the SC
   * commodity / gem names this bridge ever matches; a hypothetical non-ASCII material name (e.g.
   * one with {@code ß} or a Turkish dotted/dotless {@code I}) could fold differently and miss.
   * Callers must pass {@code Locale.ROOT}-lower-cased names so the JVM fold is deterministic.
   *
   * @param lowerNames the already-{@code Locale.ROOT}-lower-cased material names to match
   * @return the materials whose lower-cased name is in the set; empty when none match or {@code
   *     lowerNames} is empty
   */
  @Query("SELECT m FROM Material m WHERE LOWER(m.name) IN :lowerNames")
  List<Material> findByNameInIgnoreCase(@Param("lowerNames") Collection<String> lowerNames);

  /**
   * Resolution-chain step 1 for the R3 Wiki commodity sync (§8.1.1): match a Wiki commodity to a
   * local material via the SC Wiki UUID written on a previous sync.
   *
   * @param scwikiUuid the SC Wiki commodity UUID
   * @return the material if a previous sync linked it
   */
  Optional<Material> findByScwikiUuid(UUID scwikiUuid);

  /**
   * Soft-deletes SC Wiki ownership of every material whose {@code scwiki_uuid} is set, NOT in
   * {@code seenScwikiUuids}, and not already marked. Mirrors {@code
   * MaterialPriceRepository.clearStalePrices}: the caller gates this on a non-empty seen set so a
   * sync that fails to fetch the Wiki catalogue never wipes the merge state (§8.7).
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
   * Counts the live SC Wiki-linked materials: every row that carries a {@code scwiki_uuid} and is
   * not tombstoned ({@code scwiki_deleted_at IS NULL}) — the same population {@link
   * #markScwikiDeleted} maintains, minus its {@code NOT IN seen} exclusion. Used by the R3
   * commodity sync as the representative non-zero count to report when the Wiki catalogue comes
   * back {@code 304 Not Modified}: a fully-cached healthy run merges nothing, so reporting {@code
   * 0} would false-fire {@code SyncZeroItems}; reporting the live linked-row count instead keeps a
   * genuine empty-200 outage (which reports 0) as the only zero-item signal (#1182).
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
   * Candidate set of the refinery screenshot import's material matching (#434): every visible
   * material the existing refinery-order create path accepts as an input — {@code type == RAW} or
   * the admin-curated {@code isManualRawMaterial} escape hatch. The gate must mirror the create
   * path exactly, otherwise the import drafts materials the save endpoint then rejects.
   *
   * @param rawType always {@link de.greluc.krt.profit.basetool.backend.model.MaterialType#RAW};
   *     parameterized so the JPQL stays free of a hardcoded enum literal
   * @return visible refinery-input candidates, ordered by name for deterministic matching
   */
  @Query(
      """
      SELECT m FROM Material m WHERE m.isVisible = true
      AND (m.type = :rawType OR m.isManualRawMaterial = true) ORDER BY m.name
      """)
  List<Material> findRefineryInputCandidates(
      @Param("rawType") de.greluc.krt.profit.basetool.backend.model.MaterialType rawType);

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
}

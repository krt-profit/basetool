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

import de.greluc.krt.profit.basetool.backend.model.MaterialPrice;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialMatrixItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialPriceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialSellingTerminalDto;
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

/** Spring Data repository for Material Price. */
@Repository
public interface MaterialPriceRepository extends JpaRepository<MaterialPrice, UUID> {
  /** Derived Spring-Data query - returns entities matching {@code MaterialIdAndTerminalId}. */
  Optional<MaterialPrice> findByMaterialIdAndTerminalId(UUID materialId, UUID terminalId);

  /**
   * Clears the price, SCU and status columns of the given stale {@link MaterialPrice} rows at the
   * end of a UEX commodity-price sync, keeping the rows themselves.
   *
   * <p>Already-cleared rows are skipped. Pending upserts are flushed first.
   *
   * @param ids the stale row ids to clear, in caller-bounded chunks computed from {@link
   *     #findIdsWithLivePrices()}
   * @return number of rows whose prices were cleared
   */
  @Modifying(flushAutomatically = true)
  @Query(
      """
      UPDATE MaterialPrice p
      SET p.priceBuy = NULL,
          p.priceSell = NULL,
          p.scuBuy = NULL,
          p.scuSell = NULL,
          p.scuSellStock = NULL,
          p.statusBuy = false,
          p.statusSell = false
      WHERE p.id IN :ids
      AND (p.priceBuy IS NOT NULL
           OR p.priceSell IS NOT NULL
           OR p.scuBuy IS NOT NULL
           OR p.scuSell IS NOT NULL
           OR p.scuSellStock IS NOT NULL
           OR p.statusBuy = true
           OR p.statusSell = true)
      """)
  int clearPricesByIds(@Param("ids") Collection<UUID> ids);

  /**
   * Returns the id of every {@link MaterialPrice} row that still carries a price, SCU or status
   * value — the candidates for the stale-row sweep.
   *
   * @return ids of every row that currently holds a price / SCU / status value
   */
  @Query(
      """
      SELECT p.id FROM MaterialPrice p
      WHERE p.priceBuy IS NOT NULL
         OR p.priceSell IS NOT NULL
         OR p.scuBuy IS NOT NULL
         OR p.scuSell IS NOT NULL
         OR p.scuSellStock IS NOT NULL
         OR p.statusBuy = true
         OR p.statusSell = true
      """)
  List<UUID> findIdsWithLivePrices();

  /**
   * Returns paged buy/sell prices for one material across every non-hidden terminal that buys or
   * sells it, projected into {@link MaterialPriceDto}.
   */
  @Query(
      """
          SELECT new de.greluc.krt.profit.basetool.backend.model.dto.MaterialPriceDto(
              p.id, t.name, p.priceBuy, p.priceSell, p.scuBuy, p.scuSell, p.statusBuy, p.statusSell
          )
          FROM MaterialPrice p
          JOIN p.terminal t
          WHERE p.material.id = :materialId
          AND (t.hidden = false OR t.hidden IS NULL)
          AND (p.statusBuy = true OR p.statusSell = true
               OR p.priceBuy > 0 OR p.priceSell > 0)
      """)
  Page<MaterialPriceDto> findPricesByMaterialId(
      @Param("materialId") UUID materialId, Pageable pageable);

  /**
   * Returns every non-hidden terminal that currently buys the given material, ordered by sell price
   * descending (best price first; {@code NULLS LAST} so terminals with unknown price land at the
   * bottom). A terminal qualifies if {@code statusSell = true} or its {@code priceSell} is positive
   * - the OR catches both UEX import paths.
   */
  @Query(
      """
          SELECT new de.greluc.krt.profit.basetool.backend.model.dto.MaterialSellingTerminalDto(
              t.id, t.name, p.priceSell
          )
          FROM MaterialPrice p
          JOIN p.terminal t
          WHERE p.material.id = :materialId
          AND (p.statusSell = true OR p.priceSell > 0)
          AND (t.hidden = false OR t.hidden IS NULL)
          ORDER BY p.priceSell DESC NULLS LAST, t.name ASC
      """)
  List<MaterialSellingTerminalDto> findSellingTerminalsByMaterialId(
      @Param("materialId") UUID materialId);

  /**
   * Returns flattened material/terminal/price rows for the trade-matrix view, excluding hidden
   * terminals and terminals with neither a buy nor a sell price.
   *
   * <p>{@code planetName} is the effective planet anchor: the terminal's planet, else its moon's
   * planet, else the planet named like its orbit; {@code null} for system-level terminals.
   *
   * <p>Each filter is optional ({@code null} = unfiltered; ADR-0105). Pass {@code null}, never an
   * empty collection, for an unconstrained {@code IN} filter.
   *
   * @param materialNames exact material names to keep, or {@code null} for all
   * @param starSystems exact star-system names to keep, or {@code null} for all
   * @param hasLoadingDock {@code TRUE} to keep only loading-dock terminals, {@code null} for all
   * @param isAutoLoad {@code TRUE} to keep only auto-load terminals, {@code null} for all
   * @param pageable the page request
   * @return the matching (material, terminal, price) matrix rows
   */
  @Query(
      """
      SELECT new de.greluc.krt.profit.basetool.backend.model.dto.MaterialMatrixItemDto(
          m.id, m.name, CASE WHEN m.isIllegal = 1 THEN true ELSE false END,
          CASE WHEN m.isVolatileQt = 1 THEN true ELSE false END,
          CASE WHEN m.isVolatileTime = 1 THEN true ELSE false END,
          c.id, c.name, c.version, t.id, t.name, t.nickname, t.starSystemName,
          p.priceBuy, p.priceSell,
          t.cityName, t.spaceStationName, t.outpostName,
          COALESCE(t.planetName, mn.planetName, pl.name),
          t.isJumpPoint, t.hasLoadingDock,
          t.isAutoLoad
      )
      FROM MaterialPrice p
      JOIN p.material m
      LEFT JOIN m.category c
      JOIN p.terminal t
      LEFT JOIN Moon mn ON mn.name = t.moonName AND mn.starSystemName = t.starSystemName
      LEFT JOIN Planet pl ON pl.name = t.orbitName AND pl.starSystemName = t.starSystemName
      WHERE (t.hidden = false OR t.hidden IS NULL)
      AND (p.statusBuy = true OR p.statusSell = true
           OR p.priceBuy > 0 OR p.priceSell > 0)
      AND (:materialNames IS NULL OR m.name IN :materialNames)
      AND (:starSystems IS NULL OR t.starSystemName IN :starSystems)
      AND (:hasLoadingDock IS NULL OR t.hasLoadingDock = :hasLoadingDock)
      AND (:isAutoLoad IS NULL OR t.isAutoLoad = :isAutoLoad)
      """)
  Page<MaterialMatrixItemDto> findMatrixItems(
      @Param("materialNames") Collection<String> materialNames,
      @Param("starSystems") Collection<String> starSystems,
      @Param("hasLoadingDock") Boolean hasLoadingDock,
      @Param("isAutoLoad") Boolean isAutoLoad,
      Pageable pageable);

  /**
   * Returns every price row whose terminal supports cargo auto-load (i.e. usable as a profit-run
   * destination), eagerly joining material and terminal so the profit calculator can iterate
   * without N+1 queries.
   */
  @Query(
      """
          SELECT p
          FROM MaterialPrice p
          JOIN FETCH p.material m
          JOIN FETCH p.terminal t
          WHERE (t.hidden = false OR t.hidden IS NULL)
          AND t.isAutoLoad = true
      """)
  List<MaterialPrice> findAllAutoLoadPrices();

  /**
   * Same as {@link #findAllAutoLoadPrices} but restricted to terminals in the given star systems -
   * used when the profit run is constrained to a subset of the universe.
   */
  @Query(
      """
          SELECT p
          FROM MaterialPrice p
          JOIN FETCH p.material m
          JOIN FETCH p.terminal t
          WHERE (t.hidden = false OR t.hidden IS NULL)
          AND t.isAutoLoad = true
          AND t.starSystemName IN :starSystems
      """)
  List<MaterialPrice> findAllAutoLoadPricesInSystems(
      @Param("starSystems") Collection<String> starSystems);

  /**
   * The (material, terminal) key and id of every commodity price row. One query for the whole
   * matrix; the UEX sync resolves "does this pair have a row yet" in memory instead of with a
   * lookup per row (BE-PERF-09).
   *
   * @return one (parent id, terminal id, row id) row per matrix row
   */
  @Query(
      "SELECT e.material.id AS parentId, e.terminal.id AS terminalId, e.id AS id FROM MaterialPrice"
          + " e")
  List<PairKeyRef> findPriceKeyRefs();
}

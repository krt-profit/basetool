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
   * Nulls out the price / SCU / status columns on every {@link MaterialPrice} row whose id is NOT
   * in {@code seenIds}. Called at the end of a UEX commodity-price sync to neutralise rows for
   * (material, terminal) pairs that UEX no longer returns - the price-matrix sync upserts but does
   * not delete, so without this sweep a terminal that stops listing a commodity would keep its
   * last-known {@code priceBuy}/{@code priceSell} forever (e.g. a stale Quantanium buy price after
   * UEX dropped the entry).
   *
   * <p>The row itself is kept (no FK referrers, but preserving history is cheap and lets a future
   * UEX sync re-populate the same row via {@code findByMaterialIdAndTerminalId} without UUID
   * churn). Overview queries already filter on {@code priceBuy > 0} / {@code priceSell > 0}, so
   * nulled rows fall out naturally.
   *
   * <p>The {@code OR}-chain in the predicate skips rows that are already cleared, so a steady state
   * does not generate write traffic. {@code flushAutomatically = true} guarantees the preceding
   * per-row upserts are flushed before the bulk UPDATE runs so the {@code id NOT IN} predicate sees
   * the freshly-inserted rows.
   *
   * @param seenIds ids of the rows that WERE returned by UEX in the current sync; must be non-empty
   *     (caller short-circuits an empty set to avoid wiping every row on a total-failure burst)
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
      WHERE p.id NOT IN :seenIds
      AND (p.priceBuy IS NOT NULL
           OR p.priceSell IS NOT NULL
           OR p.scuBuy IS NOT NULL
           OR p.scuSell IS NOT NULL
           OR p.scuSellStock IS NOT NULL
           OR p.statusBuy = true
           OR p.statusSell = true)
      """)
  int clearStalePrices(@Param("seenIds") Collection<UUID> seenIds);

  /**
   * Returns paginated buy/sell prices for one material across every non-hidden terminal, projected
   * directly into {@link MaterialPriceDto} (no need to fetch the full {@link MaterialPrice} graph).
   * Terminals that neither buy nor sell the material are excluded - this skips rows that the UEX
   * sync's stale-row sweep ({@link #clearStalePrices}) has just neutralised so the detail page does
   * not render an army of empty-price terminals next to the handful that actually trade the
   * commodity. Matches the qualifier used by {@link #findSellingTerminalsByMaterialId}.
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
  java.util.List<MaterialSellingTerminalDto> findSellingTerminalsByMaterialId(
      @Param("materialId") UUID materialId);

  /**
   * Fully flattened material/terminal/price tuple feeding the trade-matrix view. {@code
   * isIllegal/isVolatileQt/isVolatileTime} are normalised from UEX-style {@code Integer} 0/1 flags
   * into booleans inside the JPQL via {@code CASE}; the category is left-joined because not every
   * material has one. Excludes hidden terminals.
   *
   * <p>The projected {@code planetName} is the <i>effective</i> planet-system anchor for a
   * terminal, resolved in this order via {@code COALESCE}:
   *
   * <ol>
   *   <li>{@code terminal.planet_name} (set directly when the terminal sits on a planet or a
   *       station in that planet's orbit),
   *   <li>{@code moon.planet_name} via {@code moon.name = terminal.moon_name} - covers terminals on
   *       moons whose parent planet is only indirectly known,
   *   <li>{@code planet.name} where the planet's own name matches {@code terminal.orbit_name} in
   *       the same star system - covers Lagrange-style orbits named after their host planet.
   * </ol>
   *
   * <p>The result is {@code null} for true system-level terminals (e.g. raw jump-point or
   * interplanetary Lagrange stations) that have no parent planet at all.
   *
   * <p>Excludes rows with no active buy/sell side - mirrors {@link #findPricesByMaterialId} so the
   * matrix does not surface terminals that {@link #clearStalePrices} has just neutralised after UEX
   * dropped the (material, terminal) pair.
   *
   * <p><b>Server-side filtering (ADR-0105, REQ-UI-014).</b> The four optional filter dimensions are
   * applied here so the frontend never has to fetch the whole universe and filter in memory. Each
   * dimension follows the codebase's optional-parameter idiom ({@code :param IS NULL OR …}) so a
   * {@code null} means "no filter" and an all-{@code null} call is byte-for-byte the historical
   * full-matrix query. Callers MUST pass {@code null} (never an empty collection) for an
   * unconstrained {@code IN} dimension — an empty list would render {@code IN ()} and match
   * nothing. The boolean dimensions filter to {@code true} only when the corresponding flag is
   * {@code TRUE}; {@code null} leaves them unconstrained.
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
  java.util.List<MaterialPrice> findAllAutoLoadPrices();

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
  java.util.List<MaterialPrice> findAllAutoLoadPricesInSystems(
      @Param("starSystems") java.util.Collection<String> starSystems);
}

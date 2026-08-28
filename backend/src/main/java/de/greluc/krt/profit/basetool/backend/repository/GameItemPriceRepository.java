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

import de.greluc.krt.profit.basetool.backend.model.GameItemPrice;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link GameItemPrice}. The R7 {@code UexItemPriceSyncService} upserts
 * one row per (game item, terminal) pair via {@link #findByGameItemIdAndTerminalId} and, at the end
 * of a successful run, neutralises pairs UEX no longer returns via {@link #clearStalePrices}.
 */
@Repository
public interface GameItemPriceRepository extends JpaRepository<GameItemPrice, UUID> {

  /**
   * Upsert lookup: the existing price row for one (game item, terminal) pair, or empty so the sync
   * inserts a fresh row. Backed by the {@code (game_item_id, terminal_id)} UNIQUE constraint.
   *
   * @param gameItemId the game item id
   * @param terminalId the terminal id
   * @return the existing price row if present
   */
  Optional<GameItemPrice> findByGameItemIdAndTerminalId(UUID gameItemId, UUID terminalId);

  /**
   * Nulls out the price columns on every {@link GameItemPrice} row whose id is NOT in {@code
   * seenIds}. Called at the end of a UEX item-price sync to neutralise (item, terminal) pairs UEX
   * no longer returns — the matrix sync upserts but never deletes, so without this sweep a terminal
   * that stops listing an item would keep its last-known price forever. Mirrors {@link
   * MaterialPriceRepository#clearStalePrices}.
   *
   * <p>The row itself is kept (cheap history; lets a later sync re-populate it via {@link
   * #findByGameItemIdAndTerminalId} without UUID churn). The {@code OR}-chain skips rows already
   * cleared so a steady state generates no write traffic. {@code flushAutomatically = true}
   * guarantees the preceding per-row upserts are flushed before the bulk UPDATE evaluates {@code id
   * NOT IN}.
   *
   * @param ids the stale row ids to clear, in chunks bounded by the caller; the complement is
   *     computed in Java from {@link #findIdsWithLivePrices()} so the statement never binds one
   *     parameter per row the feed returned
   * @return number of rows whose prices were cleared
   */
  @Modifying(flushAutomatically = true)
  @Query(
      """
      UPDATE GameItemPrice p
      SET p.priceBuy = NULL,
          p.priceSell = NULL,
          p.priceRent = NULL,
          p.statusBuy = NULL,
          p.statusSell = NULL
      WHERE p.id IN :ids
      AND (p.priceBuy IS NOT NULL
           OR p.priceSell IS NOT NULL
           OR p.priceRent IS NOT NULL
           OR p.statusBuy IS NOT NULL
           OR p.statusSell IS NOT NULL)
      """)
  int clearPricesByIds(@Param("ids") Collection<UUID> ids);

  /**
   * Returns the id of every {@link GameItemPrice} row that still carries a price, status or rent
   * value — the candidate set the stale-row sweep subtracts this run's seen ids from.
   *
   * <p>Exists so the sweep never binds one parameter per row UEX returned. The previous {@code id
   * NOT IN :seenIds} form did exactly that: {@code /items_prices_all} answers with 23 770 rows
   * today, which Spring Boot's IN-clause parameter padding rounds up to 32 768 bind parameters
   * against PostgreSQL's hard limit of 65 535. The next padding step is 65 536 — so at roughly 38%
   * growth the whole item-price sweep would start failing with a protocol error rather than
   * degrading. Selecting the candidates and clearing them in bounded chunks keeps the parameter
   * count flat no matter how large the matrix grows (REQ-DATA-014).
   *
   * <p>The predicate mirrors {@link #clearPricesByIds}: rows that are already cleared are not
   * candidates, so a steady state selects nothing and writes nothing.
   *
   * @return ids of every row that currently holds a price / status / rent value
   */
  @Query(
      """
      SELECT p.id FROM GameItemPrice p
      WHERE p.priceBuy IS NOT NULL
         OR p.priceSell IS NOT NULL
         OR p.priceRent IS NOT NULL
         OR p.statusBuy IS NOT NULL
         OR p.statusSell IS NOT NULL
      """)
  List<UUID> findIdsWithLivePrices();
}

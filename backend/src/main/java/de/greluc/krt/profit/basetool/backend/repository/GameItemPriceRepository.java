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
 * Spring Data repository for {@link GameItemPrice}, one row per (game item, terminal) pair,
 * maintained by the UEX item-price sync.
 */
@Repository
public interface GameItemPriceRepository extends JpaRepository<GameItemPrice, UUID> {

  /**
   * Finds the price row for one (game item, terminal) pair, the sync's upsert key.
   *
   * @param gameItemId the game item id
   * @param terminalId the terminal id
   * @return the existing price row if present
   */
  Optional<GameItemPrice> findByGameItemIdAndTerminalId(UUID gameItemId, UUID terminalId);

  /**
   * Nulls the price columns of the given stale rows, keeping the rows themselves; rows already
   * cleared are skipped.
   *
   * @param ids the stale row ids to clear, in caller-bounded chunks
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
   * value, the candidate set for the stale-row sweep (REQ-DATA-014).
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

  /**
   * The (game item, terminal) key and id of every item price row. One query for the whole matrix;
   * the UEX sync resolves "does this pair have a row yet" in memory instead of with a lookup per
   * row (BE-PERF-09).
   *
   * @return one (parent id, terminal id, row id) row per matrix row
   */
  @Query(
      "SELECT e.gameItem.id AS parentId, e.terminal.id AS terminalId, e.id AS id FROM GameItemPrice"
          + " e")
  List<PairKeyRef> findPriceKeyRefs();
}

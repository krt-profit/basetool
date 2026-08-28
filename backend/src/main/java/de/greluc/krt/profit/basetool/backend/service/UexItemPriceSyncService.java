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

package de.greluc.krt.profit.basetool.backend.service;

import de.greluc.krt.profit.basetool.backend.config.UexProperties;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexItemPriceDto;
import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.GameItemPrice;
import de.greluc.krt.profit.basetool.backend.model.Terminal;
import de.greluc.krt.profit.basetool.backend.repository.GameItemPriceRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.TerminalRepository;
import de.greluc.krt.profit.basetool.backend.support.StalePriceSweep;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * R7 UEX item-price sync (SC_WIKI_SYNC_PLAN.md §8.x / §11 R7). Walks the full UEX item-price matrix
 * ({@code /items_prices_all}) and upserts one {@code game_item_price} row per (item, terminal)
 * pair.
 *
 * <p>Resolution: {@code id_item → game_item} via {@link GameItemRepository#findByUexItemId} and
 * {@code id_terminal → terminal} via {@link TerminalRepository#findByIdTerminal}. Unlike the
 * commodity-price sync, an unknown item is <b>skipped</b> (not auto-created): {@code game_item} is
 * owned by the UEX item catalogue + Wiki backfill, which run earlier in the same scheduler tick, so
 * a price referencing an item not yet catalogued resolves on the next cycle. Unknown terminals are
 * skipped too (the universe sync owns {@code terminal}).
 *
 * <p>After the loop the ids of every touched row are passed to {@link
 * GameItemPriceRepository#clearStalePrices} to null out (item, terminal) pairs UEX no longer
 * returns. The sweep is gated on a non-empty touched-set so a run that fails on every row never
 * wipes the whole matrix; an empty upstream response short-circuits before the sweep entirely.
 *
 * <p>Gated behind {@code krt.uex.item-price-sync-enabled} (default {@code false}); ships dark. The
 * payload is the largest UEX feed (~24 000 rows) so this is the most expensive UEX sync — flip it
 * on deliberately, per the deployment runbook §7.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UexItemPriceSyncService {

  /**
   * Flush/clear the persistence context every this-many upserts so the ~24 000-row matrix never
   * accumulates in one context (§ M2 hardening). See {@link #flushAndClearIfBatchFull(int)}.
   */
  static final int FLUSH_BATCH_SIZE = 500;

  private final UexClient uexClient;
  private final UexProperties uexProperties;
  private final GameItemRepository gameItemRepository;
  private final GameItemPriceRepository gameItemPriceRepository;
  private final TerminalRepository terminalRepository;
  private final EntityManager entityManager;

  /**
   * Runs the full item-price matrix sync. No-op (with an INFO line) when the feature flag is off;
   * an empty UEX response short-circuits before the stale-row sweep.
   */
  @Transactional
  public void syncItemPrices() {
    if (!Boolean.TRUE.equals(uexProperties.getItemPriceSyncEnabled())) {
      log.info(
          "UEX item-price sync invoked but disabled (krt.uex.item-price-sync-enabled=false) —"
              + " skipping.");
      return;
    }

    log.info("Starting synchronization of UEX item prices...");
    UexClient.FetchResult<UexItemPriceDto> fetched = uexClient.getItemPrices();
    if (fetched.notModified()) {
      // Matrix byte-identical to the last run: nothing to upsert, and no stale-row sweep either —
      // every (item, terminal) pair it would clear is still in the (unchanged) feed.
      log.info("UEX item-price matrix unchanged since the last sync (304) — nothing to import.");
      return;
    }
    List<UexItemPriceDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No item prices received from UEX API. Aborting synchronization (no stale sweep).");
      return;
    }

    Set<UUID> seenPriceIds = new HashSet<>();
    Instant now = Instant.now();
    int processed = 0;
    int skipped = 0;
    for (UexItemPriceDto dto : dtos) {
      try {
        UUID savedId = upsert(dto, now);
        if (savedId != null) {
          seenPriceIds.add(savedId);
          processed++;
          flushAndClearIfBatchFull(processed);
        } else {
          skipped++;
        }
      } catch (Exception e) {
        log.error(
            "Failed to process UEX item-price dto (idItem={}, idTerminal={})",
            dto.idItem(),
            dto.idTerminal(),
            e);
      }
    }

    if (seenPriceIds.isEmpty()) {
      log.warn(
          "Skipping stale-row cleanup because no item-price row could be processed "
              + "({} dto(s) received). Refusing to wipe the entire item-price matrix.",
          dtos.size());
    } else {
      // Cleared in bounded chunks rather than with one `id NOT IN :seenIds` statement: that form
      // bound a parameter per row UEX returned (23 770 today) and would hit PostgreSQL's 65 535
      // bind-parameter ceiling as the matrix grows (REQ-DATA-014).
      int cleared =
          StalePriceSweep.clearStale(
              gameItemPriceRepository.findIdsWithLivePrices(),
              seenPriceIds,
              gameItemPriceRepository::clearPricesByIds);
      if (cleared > 0) {
        log.info("Cleared prices on {} game_item_price row(s) no longer returned by UEX.", cleared);
      }
    }
    log.info(
        "Finished UEX item-price sync: {} processed, {} skipped (unknown item / terminal).",
        processed,
        skipped);
  }

  /**
   * Upserts one item-price DTO into {@code game_item_price}. Returns the saved row id, or {@code
   * null} when the row is skipped — missing ids, an item not yet in {@code game_item}, or a
   * terminal not yet in {@code terminal}.
   *
   * @param dto inbound UEX item-price row
   * @param now timestamp to stamp on the row
   * @return the saved {@link GameItemPrice} id, or {@code null} if skipped
   */
  private UUID upsert(UexItemPriceDto dto, Instant now) {
    if (dto.idItem() == null || dto.idTerminal() == null) {
      return null;
    }
    GameItem item = gameItemRepository.findByUexItemId(dto.idItem()).orElse(null);
    if (item == null) {
      return null;
    }
    Terminal terminal = terminalRepository.findByIdTerminal(dto.idTerminal()).orElse(null);
    if (terminal == null) {
      return null;
    }

    GameItemPrice price =
        gameItemPriceRepository
            .findByGameItemIdAndTerminalId(item.getId(), terminal.getId())
            .orElseGet(
                () -> {
                  GameItemPrice fresh = new GameItemPrice();
                  fresh.setGameItem(item);
                  fresh.setTerminal(terminal);
                  return fresh;
                });

    price.setPriceBuy(dto.priceBuy());
    price.setPriceSell(dto.priceSell());
    price.setDateModified(dto.dateModified());
    price.setUexSyncedAt(now);
    return gameItemPriceRepository.save(price).getId();
  }

  /**
   * Flushes and clears the persistence context every {@link #FLUSH_BATCH_SIZE} upserts so the ~24
   * 000-row item-price matrix never accumulates in one context. Without this, Hibernate's
   * auto-flush re-dirty-checks every managed entity before each lookup query — O(n²) over the run —
   * and the heap holds every {@code game_item_price} for the transaction. {@code flush()} writes
   * pending changes first (nothing is lost), then {@code clear()} detaches them; the {@code
   * seenPriceIds} set holds row UUIDs (not entities), so the stale-row sweep after the loop is
   * unaffected, and {@code item} / {@code terminal} are re-looked-up fresh on each iteration.
   *
   * @param processedSoFar count of price rows upserted so far this run
   */
  void flushAndClearIfBatchFull(int processedSoFar) {
    if (processedSoFar > 0 && processedSoFar % FLUSH_BATCH_SIZE == 0) {
      entityManager.flush();
      entityManager.clear();
    }
  }
}

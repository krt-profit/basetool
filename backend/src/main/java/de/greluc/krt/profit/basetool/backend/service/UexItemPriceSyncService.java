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
import de.greluc.krt.profit.basetool.backend.model.GameItemPrice;
import de.greluc.krt.profit.basetool.backend.repository.GameItemPriceRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.TerminalRepository;
import de.greluc.krt.profit.basetool.backend.support.StalePriceSweep;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Syncs the UEX item-price matrix ({@code /items_prices_all}) into one {@code game_item_price} row
 * per (item, terminal) pair (REQ-DATA-005).
 *
 * <p>Items and terminals resolve through id maps read once per run; unknown ones are skipped, not
 * created. Rows are written through {@link SyncChunkWriter} after a transaction-free fetch, and
 * pairs UEX no longer returns are cleared via {@link GameItemPriceRepository#clearPricesByIds}
 * unless nothing was written. Disabled unless {@code krt.uex.item-price-sync-enabled} is set.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UexItemPriceSyncService {

  private final UexClient uexClient;
  private final UexProperties uexProperties;
  private final GameItemRepository gameItemRepository;
  private final GameItemPriceRepository gameItemPriceRepository;
  private final TerminalRepository terminalRepository;

  /** Writes the matrix in short isolated transactions after the fetch. */
  private final SyncChunkWriter chunkWriter;

  /**
   * Runs the full item-price matrix sync. No-op (with an INFO line) when the feature flag is off;
   * an empty UEX response short-circuits before the stale-row sweep. Deliberately holds no
   * transaction: the fetch runs with none open, and each chunk commits on its own.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void syncItemPrices() {
    if (!Boolean.TRUE.equals(uexProperties.itemPriceSyncEnabled())) {
      log.info(
          "UEX item-price sync invoked but disabled (krt.uex.item-price-sync-enabled=false) —"
              + " skipping.");
      return;
    }

    log.info("Starting synchronization of UEX item prices...");
    UexClient.FetchResult<UexItemPriceDto> fetched = uexClient.getItemPrices();
    if (fetched.notModified()) {
      log.info("UEX item-price matrix unchanged since the last sync (304) — nothing to import.");
      return;
    }
    List<UexItemPriceDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No item prices received from UEX API. Aborting synchronization (no stale sweep).");
      return;
    }

    Instant now = Instant.now();
    UexMatrixLookups lookups =
        chunkWriter.inNewTransaction(
            () ->
                new UexMatrixLookups(
                    gameItemRepository.findUexItemRefs(),
                    terminalRepository.findUexTerminalRefs(),
                    gameItemPriceRepository.findPriceKeyRefs()));
    SyncChunkWriter.Outcome<UUID> outcome =
        chunkWriter.write(
            dtos,
            SyncChunkWriter.DEFAULT_CHUNK_SIZE,
            chunk -> writeChunk(chunk, lookups, now),
            "item price",
            dto -> "(idItem=" + dto.idItem() + ", idTerminal=" + dto.idTerminal() + ")");
    Set<UUID> seenPriceIds = new HashSet<>(outcome.results());
    int processed = seenPriceIds.size();
    int skipped = dtos.size() - outcome.results().size() - outcome.failedRows();

    if (seenPriceIds.isEmpty()) {
      log.warn(
          "Skipping stale-row cleanup because no item-price row could be processed "
              + "({} dto(s) received). Refusing to wipe the entire item-price matrix.",
          dtos.size());
    } else {
      int cleared =
          chunkWriter.inNewTransaction(
              () ->
                  StalePriceSweep.clearStale(
                      gameItemPriceRepository.findIdsWithLivePrices(),
                      seenPriceIds,
                      gameItemPriceRepository::clearPricesByIds));
      if (cleared > 0) {
        log.info("Cleared prices on {} game_item_price row(s) no longer returned by UEX.", cleared);
      }
    }
    log.info(
        "Finished UEX item-price sync: {} processed, {} skipped (unknown item / terminal), {}"
            + " failed.",
        processed,
        skipped,
        outcome.failedRows());
  }

  /**
   * Writes one chunk of item-price rows inside its transaction: the chunk's existing rows are
   * loaded with one {@code findAllById}, updated in place or created against {@code
   * getReferenceById} parents, and saved. A row with a missing id, an item not yet in {@code
   * game_item} or a terminal not yet in {@code terminal} is skipped.
   *
   * @param chunk the rows of this chunk
   * @param lookups the preloaded id maps, extended with every row written
   * @param now timestamp to stamp on the rows
   * @return the ids of the rows written
   */
  @NotNull
  List<UUID> writeChunk(
      @NotNull List<UexItemPriceDto> chunk, @NotNull UexMatrixLookups lookups, Instant now) {
    List<UUID> existingIds = new ArrayList<>();
    for (UexItemPriceDto dto : chunk) {
      UUID itemId = lookups.parentId(dto.idItem());
      UUID terminalId = lookups.terminalId(dto.idTerminal());
      UUID priceId =
          itemId == null || terminalId == null ? null : lookups.rowId(itemId, terminalId);
      if (priceId != null) {
        existingIds.add(priceId);
      }
    }
    Map<UUID, GameItemPrice> existing =
        existingIds.isEmpty()
            ? Map.of()
            : gameItemPriceRepository.findAllById(existingIds).stream()
                .collect(Collectors.toMap(GameItemPrice::getId, Function.identity()));
    Map<UexMatrixLookups.Pair, GameItemPrice> written = new LinkedHashMap<>();
    for (UexItemPriceDto dto : chunk) {
      UUID itemId = lookups.parentId(dto.idItem());
      UUID terminalId = lookups.terminalId(dto.idTerminal());
      if (itemId == null || terminalId == null) {
        continue;
      }
      UexMatrixLookups.Pair key = new UexMatrixLookups.Pair(itemId, terminalId);
      GameItemPrice price = written.get(key);
      if (price == null) {
        UUID priceId = lookups.rowId(itemId, terminalId);
        price = priceId == null ? null : existing.get(priceId);
      }
      if (price == null) {
        price = new GameItemPrice();
        price.setGameItem(gameItemRepository.getReferenceById(itemId));
        price.setTerminal(terminalRepository.getReferenceById(terminalId));
      }
      price.setPriceBuy(dto.priceBuy());
      price.setPriceSell(dto.priceSell());
      price.setDateModified(dto.dateModified());
      price.setUexSyncedAt(now);
      written.put(key, price);
    }
    List<UUID> ids = new ArrayList<>();
    for (Map.Entry<UexMatrixLookups.Pair, GameItemPrice> entry : written.entrySet()) {
      UUID id = gameItemPriceRepository.save(entry.getValue()).getId();
      lookups.rememberRow(entry.getKey().parentId(), entry.getKey().terminalId(), id);
      ids.add(id);
    }
    return ids;
  }
}

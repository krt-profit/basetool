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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexItemDto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * Merges the rows second UEX listings created into the game rows of their items after a complete
 * UEX item fetch (REQ-DATA-044). The twins are found in one read; each merge then runs in a
 * transaction of its own, so a failing pair rolls back alone and is retried by the next run.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class UexItemTwinMerger {

  private final GameItemRepository items;
  private final ItemReconciliationService reconciliation;
  private final SyncReportService reports;

  /** Runs the read and each merge in a transaction of its own. */
  private final SyncChunkWriter chunkWriter;

  /**
   * Merges every twin the fetched listings show; holds no transaction itself.
   *
   * @param listings the fetched UEX item listings
   * @param census the fetch's identity census; an incomplete one merges nothing
   * @param runId the UEX item sync run
   * @return the number of twins merged
   */
  int mergeTwins(
      @NotNull List<UexItemDto> listings, @NotNull UexIdentityCensus census, @NotNull UUID runId) {
    if (!census.complete()) {
      return 0;
    }
    List<UexItemTwins.Twin> twins = chunkWriter.inNewTransaction(() -> findTwins(listings, census));
    int merged = 0;
    for (UexItemTwins.Twin twin : twins) {
      try {
        if (Boolean.TRUE.equals(
            chunkWriter.inNewTransaction(() ->
                reconciliation.mergeUexTwin(twin.twinId(), twin.targetId(), twin.rule(), runId)))) {
          merged++;
        }
      } catch (RuntimeException e) {
        reports.recordFailed(1);
        log.error("Failed to merge UEX item twin {} into {}", twin.twinId(), twin.targetId(), e);
      }
    }
    reports.recordApplied(merged);
    return merged;
  }

  /**
   * Finds the stored twins of the fetched listings in the caller's transaction.
   *
   * @param listings the fetched UEX item listings
   * @param census the complete fetch's identity census
   * @return the twins with the game rows they merge into
   */
  @NotNull
  List<UexItemTwins.Twin> findTwins(
      @NotNull List<UexItemDto> listings, @NotNull UexIdentityCensus census) {
    Map<Integer, UexItemDto> byId = new HashMap<>();
    for (UexItemDto listing : listings) {
      if (listing.id() != null) {
        byId.putIfAbsent(listing.id(), listing);
      }
    }
    List<Listed> twins = new ArrayList<>();
    Set<String> names = new HashSet<>();
    Set<UUID> uuids = new HashSet<>();
    for (GameItem row :
        items.findByExternalUuidIsNullAndSourceSystems(GameItemSourceSystem.UEX_ONLY)) {
      UexItemDto listing = byId.get(row.getUexItemId());
      if (listing == null || !UexItemTwins.isTwinCandidate(row)) {
        continue;
      }
      String name = UexIdentityCensus.nameKey(listing.name());
      if (name != null) {
        twins.add(new Listed(row, listing));
        names.add(name);
        UUID uuid = UexValues.parseUuid(listing.uuid());
        if (uuid != null) {
          uuids.add(uuid);
        }
      }
    }
    if (twins.isEmpty()) {
      return List.of();
    }
    List<GameItem> candidates = new ArrayList<>(items.findAllByFoldedNameOrUexName(names));
    if (!uuids.isEmpty()) {
      candidates.addAll(items.findAllHoldingGameUuids(uuids));
    }
    UexItemTwins rule = new UexItemTwins(candidates, census);
    List<UexItemTwins.Twin> found = new ArrayList<>();
    for (Listed twin : twins) {
      UexItemTwins.Twin match = rule.find(twin.row(), twin.listing());
      if (match != null) {
        found.add(match);
      }
    }
    return found;
  }

  /**
   * A possible twin with its listing of the fetch.
   *
   * @param row the UEX-only row
   * @param listing the fetched listing of its UEX item id
   */
  private record Listed(@NotNull GameItem row, @NotNull UexItemDto listing) {}
}

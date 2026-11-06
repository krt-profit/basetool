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

import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItemStatRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiItemDto;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;

/** Fetches missing blueprint-output stat bases from version-pinned Wiki item details. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScWikiItemStatFollowUp {

  private final ScWikiClient scWikiClient;
  private final ScWikiProperties properties;
  private final GameItemStatRepository gameItemStatRepository;
  private final SyncReportService syncReportService;

  /**
   * Fetches each missing item's detail and hands it to {@code fill}; a failing item is logged and
   * skipped.
   *
   * @param fill persists one successfully fetched payload with its item UUID
   * @return the number of items fetched and filled
   */
  public int fetchMissing(@NotNull BiConsumer<UUID, ScWikiItemDto> fill) {
    try (ScWikiClient.SyncRun _ = scWikiClient.openSyncRun()) {
      return fetchWithinRun(fill);
    }
  }

  /** Fetches missing stat payloads under the selected run version. */
  private int fetchWithinRun(BiConsumer<UUID, ScWikiItemDto> fill) {
    List<UUID> missing = gameItemStatRepository.findBlueprintOutputUuidsWithoutStats();
    int fetched = 0;
    for (UUID uuid : missing) {
      try {
        ScWikiItemDto dto =
            scWikiClient.fetchOne(properties.itemsEndpoint(), uuid, ScWikiItemDto.class, "item");
        if (dto == null) {
          continue;
        }
        fill.accept(uuid, dto);
        fetched++;
        syncReportService.recordApplied(1);
      } catch (OptimisticLockingFailureException _) {
        syncReportService.recordFailed(1);
        log.warn("Optimistic lock collision filling stats of SC Wiki item {}; deferring", uuid);
      } catch (Exception e) {
        syncReportService.recordFailed(1);
        log.error("Failed to fetch stats of SC Wiki item {}", uuid, e);
      }
    }
    if (!missing.isEmpty()) {
      log.info(
          "SC Wiki item backfill: fetched {} of {} blueprint output(s) without stat values in"
              + " full.",
          fetched,
          missing.size());
    }
    return fetched;
  }
}

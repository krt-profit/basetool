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

/**
 * Fetches the full SC Wiki payload of every item an active blueprint crafts that still has no
 * stored stat base value, since the residual {@code /api/items} list rows carry no stat blocks
 * (REQ-DATA-027).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScWikiItemStatFollowUp {

  private final ScWikiClient scWikiClient;
  private final ScWikiProperties properties;
  private final GameItemStatRepository gameItemStatRepository;

  /**
   * Fetches each missing item's detail and hands it to {@code fill}; a failing item is logged and
   * skipped.
   *
   * @param fill persists one fetched payload, given the item's UUID and the payload or {@code null}
   *     when the Wiki does not know the item
   * @return the number of items fetched and filled
   */
  public int fetchMissing(@NotNull BiConsumer<UUID, ScWikiItemDto> fill) {
    List<UUID> missing = gameItemStatRepository.findBlueprintOutputUuidsWithoutStats();
    int fetched = 0;
    for (UUID uuid : missing) {
      scWikiClient.paceForRateLimit();
      try {
        fill.accept(
            uuid,
            scWikiClient.fetchOne(properties.itemsEndpoint(), uuid, ScWikiItemDto.class, "item"));
        fetched++;
      } catch (OptimisticLockingFailureException _) {
        log.warn("Optimistic lock collision filling stats of SC Wiki item {}; deferring", uuid);
      } catch (Exception e) {
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

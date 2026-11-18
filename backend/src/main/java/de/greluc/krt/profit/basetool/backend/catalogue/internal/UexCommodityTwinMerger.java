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

import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexCommodityDto;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;

/**
 * Merges the materials second UEX listings keep into the materials of their commodities after a
 * complete UEX commodity fetch (REQ-DATA-046). The twins are found in one read; each merge then
 * runs in a transaction of its own, so a failing pair rolls back alone and is tried again by the
 * next run.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UexCommodityTwinMerger {

  /** The {@code external_sync_report} aggregate of a commodity event. */
  private static final String REPORT_AGGREGATE = "commodity";

  private final MaterialRepository materials;
  private final MaterialMergeService merges;
  private final SyncReportService reports;

  /** Runs the read and each merge in a transaction of its own. */
  private final SyncChunkWriter chunkWriter;

  /**
   * Merges every twin the fetched listings show; holds no transaction itself.
   *
   * @param listings the listings of a complete UEX commodity fetch
   * @param runId the UEX commodity sync run
   * @return the number of twins merged
   */
  public int mergeTwins(@NotNull List<UexCommodityDto> listings, @NotNull UUID runId) {
    Set<Integer> listed =
        listings.stream()
            .map(UexCommodityDto::id)
            .filter(Objects::nonNull)
            .collect(Collectors.toUnmodifiableSet());
    List<UexCommodityTwins.Twin> twins =
        chunkWriter.inNewTransaction(() -> UexCommodityTwins.find(materials.findAll(), listed));
    int merged = 0;
    for (UexCommodityTwins.Twin twin : twins) {
      try {
        if (Boolean.TRUE.equals(chunkWriter.inNewTransaction(() -> merge(twin, runId)))) {
          merged++;
        }
      } catch (RuntimeException e) {
        reports.recordFailed(1);
        log.error(
            "Failed to merge UEX commodity twin {} into {}", twin.twinId(), twin.survivorId(), e);
      }
    }
    reports.recordApplied(merged);
    return merged;
  }

  /**
   * Merges one twin inside the caller's transaction and reports it.
   *
   * @param twin the twin and the material it merges into
   * @param runId the UEX commodity sync run
   * @return whether the merge happened
   */
  private boolean merge(@NotNull UexCommodityTwins.Twin twin, @NotNull UUID runId) {
    if (!merges.merge(twin.twinId(), twin.survivorId(), twin.rule())) {
      return false;
    }
    materials
        .findById(twin.survivorId())
        .ifPresent(survivor ->
            reports.logUexEvent(
                runId,
                SyncEventType.SOURCE_RECORDS_MERGED,
                REPORT_AGGREGATE,
                survivor.getScwikiUuid(),
                survivor.getName(),
                SyncReportDetails.of("retiredMaterial", twin.twinId())
                    .with("survivorMaterial", twin.survivorId())
                    .with("rule", twin.rule())
                    .toString()));
    return true;
  }
}

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

package de.greluc.krt.profit.basetool.backend.service.scwiki;

import de.greluc.krt.profit.basetool.backend.config.ScWikiProperties;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiManufacturerDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiResponseDto;
import de.greluc.krt.profit.basetool.backend.integration.scwiki.ScWikiClient;
import de.greluc.krt.profit.basetool.backend.model.Manufacturer;
import de.greluc.krt.profit.basetool.backend.model.SyncEventType;
import de.greluc.krt.profit.basetool.backend.model.SyncSourceSystem;
import de.greluc.krt.profit.basetool.backend.repository.ManufacturerRepository;
import de.greluc.krt.profit.basetool.backend.service.SyncReportService;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Reconciles SC Wiki manufacturers ({@code /api/manufacturers}) with the existing UEX {@code
 * manufacturer} rows by stamping the Wiki cross-reference columns.
 *
 * <p>Enrichment only: never inserts a row and never overwrites UEX-owned columns. A row already
 * linked to a different Wiki UUID is left alone and logged as {@link
 * SyncEventType#MANUFACTURER_MISMATCH}. Gated behind {@code krt.scwiki.manufacturer-sync-enabled}
 * (default {@code false}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScWikiManufacturerSyncService {

  private final ScWikiClient scWikiClient;
  private final ScWikiProperties properties;
  private final ManufacturerRepository manufacturerRepository;
  private final SyncReportService syncReportService;

  /**
   * Runs the manufacturer reconciliation; a no-op when the flag is off, and an empty response skips
   * the orphan sweep.
   *
   * @return the number of {@code manufacturer} rows written ({@code linked + refreshed}), {@code 0}
   *     when disabled or empty, or {@link ManufacturerRepository#countLiveScwikiManufacturers() the
   *     live reconciled-manufacturer count} on a {@code 304 Not Modified}
   */
  @Transactional
  public int syncManufacturers() {
    if (!Boolean.TRUE.equals(properties.manufacturerSyncEnabled())) {
      log.info(
          "SC Wiki manufacturer sync invoked but disabled "
              + "(krt.scwiki.manufacturer-sync-enabled=false) — skipping.");
      return 0;
    }

    log.info("Starting SC Wiki manufacturer reconciliation...");
    ScWikiClient.FetchResult<ScWikiManufacturerDto> result =
        scWikiClient.fetchAllPagesResult(
            properties.manufacturersEndpoint(),
            new ParameterizedTypeReference<ScWikiResponseDto<ScWikiManufacturerDto>>() {},
            "manufacturers");
    if (result.notModified()) {
      long live = manufacturerRepository.countLiveScwikiManufacturers();
      log.info(
          "SC Wiki manufacturer catalogue unchanged since last sync (304) — reporting {} live"
              + " reconciled manufacturer row(s) instead of 0.",
          live);
      return (int) live;
    }
    List<ScWikiManufacturerDto> fetched = result.data();
    if (fetched.isEmpty()) {
      log.warn("No manufacturers received from SC Wiki API. Aborting reconciliation (no sweep).");
      return 0;
    }

    UUID runId = syncReportService.beginRun();
    Instant now = Instant.now();
    Set<UUID> seen = new HashSet<>();
    int linked = 0;
    int refreshed = 0;
    int conflicts = 0;
    int unmatched = 0;

    for (ScWikiManufacturerDto dto : fetched) {
      if (dto.uuid() == null) {
        continue;
      }
      try {
        Manufacturer match = resolve(dto);
        if (match == null) {
          unmatched++;
          continue;
        }
        UUID existingLink = match.getScwikiUuid();
        if (existingLink != null && !existingLink.equals(dto.uuid())) {
          syncReportService.logScwikiEvent(
              runId,
              SyncEventType.MANUFACTURER_MISMATCH,
              "manufacturer",
              dto.uuid(),
              dto.name(),
              "Wiki manufacturer matched local '"
                  + match.getName()
                  + "' which is already linked to a different Wiki UUID — left untouched.");
          conflicts++;
          continue;
        }

        final boolean firstLink = existingLink == null;
        seen.add(dto.uuid());
        match.setScwikiUuid(dto.uuid());
        if (StringUtils.hasText(dto.code())) {
          match.setScwikiCode(dto.code());
        }
        match.setScwikiSyncedAt(now);
        match.setScwikiDeletedAt(null);
        manufacturerRepository.save(match);

        if (firstLink) {
          linked++;
          syncReportService.logScwikiEvent(
              runId,
              SyncEventType.MANUFACTURER_LINKED,
              "manufacturer",
              dto.uuid(),
              match.getName(),
              "Linked Wiki manufacturer (code "
                  + dto.code()
                  + ") to local '"
                  + match.getName()
                  + "'.");
        } else {
          refreshed++;
        }
      } catch (Exception e) {
        log.error("Failed to reconcile SC Wiki manufacturer {}", dto.uuid(), e);
      }
    }

    ScWikiOrphanSweep.sweepDeletedOrphans(
        seen,
        result.complete(),
        s -> manufacturerRepository.markScwikiDeletedExcept(s, now),
        log,
        "manufacturer");
    syncReportService.pruneRuns(SyncSourceSystem.SCWIKI);
    log.info(
        "Finished SC Wiki manufacturer reconciliation: {} newly linked, {} refreshed, {} conflicts,"
            + " {} unmatched.",
        linked,
        refreshed,
        conflicts,
        unmatched);
    return linked + refreshed;
  }

  /**
   * Resolves a Wiki manufacturer to a local row by {@code scwiki_uuid}, then case-insensitive name,
   * then case-insensitive {@code abbreviation == code}.
   *
   * @param dto the Wiki manufacturer payload
   * @return the matching local manufacturer, or {@code null}
   */
  @Nullable
  private Manufacturer resolve(@NotNull ScWikiManufacturerDto dto) {
    Optional<Manufacturer> byUuid = manufacturerRepository.findByScwikiUuid(dto.uuid());
    if (byUuid.isPresent()) {
      return byUuid.get();
    }
    if (StringUtils.hasText(dto.name())) {
      Optional<Manufacturer> byName = manufacturerRepository.findByNameIgnoreCase(dto.name());
      if (byName.isPresent()) {
        return byName.get();
      }
    }
    if (StringUtils.hasText(dto.code())) {
      return manufacturerRepository
          .findFirstByAbbreviationIgnoreCaseOrderByCreatedAtAsc(dto.code())
          .orElse(null);
    }
    return null;
  }
}

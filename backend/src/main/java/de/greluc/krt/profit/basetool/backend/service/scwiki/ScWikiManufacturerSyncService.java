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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * R6 SC Wiki manufacturer reconciliation (SC_WIKI_SYNC_PLAN.md §11 R6 / §6.4). Paginates {@code
 * /api/manufacturers} and stamps the Wiki cross-reference columns ({@code scwiki_uuid} / {@code
 * scwiki_code}) onto the {@code manufacturer} rows the UEX sync already created.
 *
 * <p>It is an <b>enrichment-only</b> pass: it never inserts a row (a Wiki manufacturer with no
 * local UEX counterpart is simply skipped — {@code manufacturer} has no {@code WIKI_ONLY} concept
 * and its {@code name} is {@code NOT NULL UNIQUE}) and it never overwrites the UEX-canonical {@code
 * name} / {@code abbreviation} / {@code industry}. Only {@code scwiki_uuid}, {@code scwiki_code}
 * and {@code scwiki_synced_at} are written (and {@code scwiki_deleted_at} cleared) — mirroring the
 * §6.3.5 "each side owns its columns" rule used by the item / vehicle syncs.
 *
 * <p>Resolution chain (§6.4): {@code scwiki_uuid} → case-insensitive {@code name} →
 * case-insensitive {@code abbreviation == code} (the §6.4 chain's third step is {@code
 * industry+name}, but the Wiki manufacturer payload exposes no industry; the local {@code
 * abbreviation} matched against the Wiki {@code code} is the available analogue and lifts the link
 * rate for companies whose full name differs between catalogues). A candidate already linked to a
 * <em>different</em> Wiki UUID is left untouched and logged {@link
 * SyncEventType#MANUFACTURER_MISMATCH} rather than hijacked.
 *
 * <p>Gated behind {@code krt.scwiki.manufacturer-sync-enabled} (default {@code false}); ships dark.
 * An empty Wiki response short-circuits before the orphan sweep, which — like every other sync —
 * only fires on a non-empty seen set (§8.7) so an outage never wipes the reconciliation state.
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
   * Runs the manufacturer reconciliation. No-op (with an INFO line) when the feature flag is off;
   * an empty Wiki response short-circuits before the orphan sweep.
   *
   * <p>Returns the number of {@code manufacturer} rows this run wrote — first-time links plus
   * refreshed links (conflicts and unmatched rows excluded, since they leave the row untouched) —
   * which {@link ScWikiScheduler} accumulates into {@code
   * basetool_scheduled_job_items_total{job="scwiki_sync"}}. The disabled and <em>genuine</em>
   * empty-response short-circuits return {@code 0} so a Wiki outage surfaces as a zero-item run
   * ({@code SyncZeroItems}, #1041 item 2). A {@code 304 Not Modified} response is <b>not</b> such
   * an outage — the catalogue is merely unchanged — so this reports {@link
   * ManufacturerRepository#countLiveScwikiManufacturers() the live reconciled-manufacturer count}
   * instead of {@code 0}, keeping a fully-cached healthy run from false-firing {@code
   * SyncZeroItems} (#1182).
   *
   * @return the number of {@code manufacturer} rows written this run ({@code linked + refreshed}),
   *     or the live reconciled-manufacturer count on a {@code 304 Not Modified} (unchanged)
   *     catalogue
   */
  @Transactional
  public int syncManufacturers() {
    if (!Boolean.TRUE.equals(properties.getManufacturerSyncEnabled())) {
      log.info(
          "SC Wiki manufacturer sync invoked but disabled "
              + "(krt.scwiki.manufacturer-sync-enabled=false) — skipping.");
      return 0;
    }

    log.info("Starting SC Wiki manufacturer reconciliation...");
    ScWikiClient.FetchResult<ScWikiManufacturerDto> result =
        scWikiClient.fetchAllPagesResult(
            properties.getManufacturersEndpoint(),
            new ParameterizedTypeReference<ScWikiResponseDto<ScWikiManufacturerDto>>() {},
            "manufacturers");
    if (result.notModified()) {
      // Catalogue unchanged since the last sync (ETag 304): nothing to reconcile, but this is a
      // healthy run — report the live reconciled-manufacturer count so an all-304 run is not read
      // as a zero-item outage (#1182). A genuine empty-200 falls through to isEmpty() and reports
      // 0.
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

        // Captured before setScwikiUuid() below mutates getScwikiUuid(); final so the
        // declaration-to-use distance check tolerates the gap.
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
        seen, s -> manufacturerRepository.markScwikiDeletedExcept(s, now), log, "manufacturer");
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
   * Resolves a Wiki manufacturer to an existing local row via the §6.4 chain: {@code scwiki_uuid} →
   * case-insensitive {@code name} → case-insensitive {@code abbreviation == code}. Returns {@code
   * null} when nothing matches — the reconciliation never creates a manufacturer.
   *
   * @param dto the Wiki manufacturer payload
   * @return the matching local manufacturer, or {@code null}
   */
  private Manufacturer resolve(ScWikiManufacturerDto dto) {
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

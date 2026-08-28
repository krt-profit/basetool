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
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiResponseDto;
import de.greluc.krt.profit.basetool.backend.dto.scwiki.ScWikiVehicleDto;
import de.greluc.krt.profit.basetool.backend.integration.scwiki.ScWikiClient;
import de.greluc.krt.profit.basetool.backend.model.GameItemSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.SyncSourceSystem;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
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
 * R4 SC Wiki vehicle sync (SC_WIKI_SYNC_PLAN.md §8.6). Paginates {@code /api/vehicles} and fills
 * the Wiki-owned columns on the {@code ship_type} rows the UEX vehicle sync already created,
 * matched by {@code external_uuid} (falling back to case-insensitive name, which also backfills
 * {@code external_uuid} on a legacy row).
 *
 * <p>Conflict policy (§6.3.5): the Wiki sync writes {@code scwiki_slug} / {@code game_name} /
 * {@code description_de}, and fills {@code description_en} / {@code class_name} / {@code
 * vehicle_inventory_scu} only when UEX left them blank — it never overwrites the UEX-canonical
 * {@code name}, the 36 capability {@code is_*} flags, dimensions, fuel or urls. It flips {@code
 * source_systems} {@code UEX_ONLY → BOTH}. A vehicle with no local row becomes a fresh {@code
 * WIKI_ONLY} ship type.
 *
 * <p>Gated behind {@code krt.scwiki.vehicle-sync-enabled} (default {@code false}); ships dark.
 * Empty Wiki responses short-circuit before the orphan sweep (§8.7).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ScWikiVehicleSyncService {

  private final ScWikiClient scWikiClient;
  private final ScWikiProperties properties;
  private final ShipTypeRepository shipTypeRepository;
  private final SyncReportService syncReportService;

  /**
   * Runs the full Wiki vehicle fill. No-op (with an INFO line) when the feature flag is off. An
   * empty Wiki response short-circuits before the orphan sweep.
   *
   * <p>Returns the number of {@code ship_type} rows this run wrote — matched rows linked plus fresh
   * {@code WIKI_ONLY} rows created — which {@link ScWikiScheduler} accumulates into {@code
   * basetool_scheduled_job_items_total{job="scwiki_sync"}}. The disabled and <em>genuine</em>
   * empty-response short-circuits return {@code 0} so a Wiki outage surfaces as a zero-item run
   * ({@code SyncZeroItems}, #1041 item 2). A {@code 304 Not Modified} response is <b>not</b> such
   * an outage — the catalogue is merely unchanged — so this reports {@link
   * ShipTypeRepository#countLiveScwikiShipTypes() the live Wiki-linked ship-type count} instead of
   * {@code 0}, keeping a fully-cached healthy run from false-firing {@code SyncZeroItems} (#1182).
   *
   * @return the number of {@code ship_type} rows written this run ({@code linked} plus {@code
   *     createdWikiOnly}), or the live Wiki-linked ship-type count on a {@code 304 Not Modified}
   *     (unchanged) catalogue
   */
  @Transactional
  public int syncVehicles() {
    if (!Boolean.TRUE.equals(properties.getVehicleSyncEnabled())) {
      log.info(
          "SC Wiki vehicle sync invoked but disabled "
              + "(krt.scwiki.vehicle-sync-enabled=false) — skipping.");
      return 0;
    }

    log.info("Starting SC Wiki vehicle sync...");
    ScWikiClient.FetchResult<ScWikiVehicleDto> result =
        scWikiClient.fetchAllPagesResult(
            properties.getVehiclesEndpoint(),
            new ParameterizedTypeReference<ScWikiResponseDto<ScWikiVehicleDto>>() {},
            "vehicles",
            null,
            null,
            // A vehicle row carries its whole port / shield / power tree, so this walk asks for a
            // smaller page than every other endpoint: at the shared 200 the first page alone is
            // 10.4 MB against the client's 16 MB codec ceiling, and crossing it stops the sync
            // silently rather than loudly.
            properties.getVehiclesPageSize());
    if (result.notModified()) {
      // Catalogue unchanged since the last sync (ETag 304): nothing to fill, but this is a healthy
      // run — report the live Wiki-linked ship-type count so an all-304 run is not read as a
      // zero-item outage (#1182). A genuine empty-200 falls through to isEmpty() and reports 0.
      long live = shipTypeRepository.countLiveScwikiShipTypes();
      log.info(
          "SC Wiki vehicle catalogue unchanged since last sync (304) — reporting {} live"
              + " Wiki-linked ship_type row(s) instead of 0.",
          live);
      return (int) live;
    }
    List<ScWikiVehicleDto> fetched = result.data();
    if (fetched.isEmpty()) {
      log.warn("No vehicles received from SC Wiki API. Aborting sync (no orphan sweep).");
      return 0;
    }

    Instant now = Instant.now();
    Set<UUID> seen = new HashSet<>();
    int linked = 0;
    int createdWikiOnly = 0;

    for (ScWikiVehicleDto dto : fetched) {
      if (dto.uuid() == null) {
        continue;
      }
      try {
        Optional<ShipType> match = shipTypeRepository.findByExternalUuid(dto.uuid());
        if (match.isEmpty() && StringUtils.hasText(dto.name())) {
          match = shipTypeRepository.findByNameIgnoreCase(dto.name());
        }
        ShipType st = match.orElse(null);
        boolean isNew = st == null;
        if (isNew) {
          st = new ShipType();
          st.setName(dto.name());
          st.setExternalUuid(dto.uuid());
          st.setSourceSystems(GameItemSourceSystem.WIKI_ONLY);
          createdWikiOnly++;
        } else {
          if (st.getExternalUuid() == null) {
            st.setExternalUuid(dto.uuid());
          }
          linked++;
        }
        seen.add(dto.uuid());
        applyWikiFields(st, dto, now);
        if (st.getSourceSystems() == GameItemSourceSystem.UEX_ONLY) {
          st.setSourceSystems(GameItemSourceSystem.BOTH);
        }
        shipTypeRepository.save(st);
      } catch (Exception e) {
        log.error("Failed to process SC Wiki vehicle dto: {}", dto.uuid(), e);
      }
    }

    ScWikiOrphanSweep.sweepDeletedOrphans(
        seen,
        result.complete(),
        s -> shipTypeRepository.markScwikiDeletedExcept(s, now),
        log,
        "ship_type");
    syncReportService.pruneRuns(SyncSourceSystem.SCWIKI);
    log.info(
        "Finished SC Wiki vehicle sync: {} linked, {} created WIKI_ONLY.", linked, createdWikiOnly);
    return linked + createdWikiOnly;
  }

  /**
   * Writes the Wiki-owned columns onto a ship type. {@code scwiki_slug} / {@code game_name} /
   * {@code description_de} are always taken from the Wiki; {@code description_en} / {@code
   * class_name} / {@code vehicle_inventory_scu} are filled only when UEX left them blank so the UEX
   * value wins where both have one. The UEX-canonical {@code name}, capability flags, dimensions,
   * fuel and urls are never touched.
   *
   * @param st the ship type to update
   * @param dto the Wiki vehicle payload
   * @param now timestamp for {@code scwiki_synced_at}
   */
  private void applyWikiFields(ShipType st, ScWikiVehicleDto dto, Instant now) {
    st.setScwikiSlug(dto.slug());
    st.setGameName(dto.gameName());
    if (!StringUtils.hasText(st.getClassName())) {
      st.setClassName(dto.className());
    }
    if (st.getVehicleInventoryScu() == null) {
      st.setVehicleInventoryScu(dto.vehicleInventory());
    }
    if (dto.description() != null) {
      String en = dto.description().get("en_EN");
      if (!StringUtils.hasText(st.getDescriptionEn()) && StringUtils.hasText(en)) {
        st.setDescriptionEn(en);
      }
      st.setDescriptionDe(dto.description().get("de_DE"));
    }
    st.setScwikiSyncedAt(now);
    st.setScwikiDeletedAt(null);
  }
}

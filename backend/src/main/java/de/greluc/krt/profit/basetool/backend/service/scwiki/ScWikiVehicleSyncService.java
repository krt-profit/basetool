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
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Fills the Wiki-owned columns of the {@code ship_type} rows from the SC Wiki vehicle feed ({@code
 * /api/vehicles}), matched by {@code external_uuid} or case-insensitive name.
 *
 * <p>Never overwrites UEX-canonical columns; an unmatched vehicle becomes a {@code WIKI_ONLY} ship
 * type. Gated behind {@code krt.scwiki.vehicle-sync-enabled} (default {@code false}).
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
   * Runs the full Wiki vehicle fill; a no-op when the flag is off, and an empty response skips the
   * orphan sweep.
   *
   * @return the number of {@code ship_type} rows written ({@code linked} plus {@code
   *     createdWikiOnly}), {@code 0} when disabled or empty, or {@link
   *     ShipTypeRepository#countLiveScwikiShipTypes() the live Wiki-linked ship-type count} on a
   *     {@code 304 Not Modified}
   */
  @Transactional
  public int syncVehicles() {
    if (!Boolean.TRUE.equals(properties.vehicleSyncEnabled())) {
      log.info(
          "SC Wiki vehicle sync invoked but disabled "
              + "(krt.scwiki.vehicle-sync-enabled=false) — skipping.");
      return 0;
    }

    log.info("Starting SC Wiki vehicle sync...");
    ScWikiClient.FetchResult<ScWikiVehicleDto> result =
        scWikiClient.fetchAllPagesResult(
            properties.vehiclesEndpoint(),
            new ParameterizedTypeReference<ScWikiResponseDto<ScWikiVehicleDto>>() {},
            "vehicles",
            null,
            null,
            properties.vehiclesPageSize());
    if (result.notModified()) {
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
   * Writes the Wiki-owned columns onto a ship type; {@code description_en}, {@code class_name} and
   * {@code vehicle_inventory_scu} are filled only when UEX left them blank.
   *
   * @param st the ship type to update
   * @param dto the Wiki vehicle payload
   * @param now timestamp for {@code scwiki_synced_at}
   */
  private void applyWikiFields(@NotNull ShipType st, @NotNull ScWikiVehicleDto dto, Instant now) {
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

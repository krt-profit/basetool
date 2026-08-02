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

import de.greluc.krt.profit.basetool.backend.dto.uex.UexStarSystemDto;
import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.model.StarSystem;
import de.greluc.krt.profit.basetool.backend.repository.StarSystemRepository;
import de.greluc.krt.profit.basetool.backend.support.LogSafe;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports the UEX star-system catalog.
 *
 * <p>Lookup priority: UEX {@code id_system}, then by name (legacy migration path for systems that
 * pre-date the {@code id_system} column). Unknown systems are auto-created with a fallback name so
 * the universe sync stays self-healing. Per-field dirty checking minimizes write traffic — only
 * rows that actually changed get persisted.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UexStarSystemService {

  /**
   * Cap for the upstream-supplied star-system name in log lines. UEX is a third party we do not
   * control, so the value is untrusted free text and goes through {@link LogSafe} first; 64
   * characters comfortably fit any real system name.
   */
  private static final int MAX_NAME_LOG_LENGTH = 64;

  private final UexClient uexClient;
  private final StarSystemRepository starSystemRepository;

  /**
   * Pulls the star-system catalog and upserts each row. An unchanged feed ({@code 304}) and an
   * empty response are both no-ops, but only the latter is reported as a problem — the former is a
   * healthy fully-cached run. Neither wipes local data.
   */
  @Transactional
  public void fetchAndProcessStarSystems() {
    log.info("Starting synchronization of UEX star systems...");
    UexClient.FetchResult<UexStarSystemDto> fetched = uexClient.getStarSystems();
    if (fetched.notModified()) {
      log.info(
          "UEX star system catalogue unchanged since the last sync (304) — nothing to import.");
      return;
    }
    List<UexStarSystemDto> dtos = fetched.data();

    if (dtos.isEmpty()) {
      log.warn("No data received from UEX API. Aborting star system synchronization.");
      return;
    }

    int processed = 0;
    for (UexStarSystemDto dto : dtos) {
      try {
        processSingleDto(dto);
        processed++;
      } catch (Exception e) {
        log.error(
            "Failed to process star system dto (id={}, name='{}')",
            dto.id(),
            LogSafe.text(dto.name(), MAX_NAME_LOG_LENGTH),
            e);
      }
    }

    log.info("Finished synchronization. Processed {} star systems.", processed);
  }

  private void processSingleDto(UexStarSystemDto dto) {
    if (dto.id() == null) {
      log.warn(
          "Missing ID in star system DTO (name='{}')",
          LogSafe.text(dto.name(), MAX_NAME_LOG_LENGTH));
      return;
    }

    StarSystem starSystem =
        starSystemRepository
            .findByIdSystem(dto.id())
            .orElseGet(
                () ->
                    starSystemRepository
                        .findByName(dto.name())
                        .map(
                            s -> {
                              s.setIdSystem(dto.id());
                              return starSystemRepository.save(s);
                            })
                        .orElseGet(
                            () -> {
                              StarSystem newSystem = new StarSystem();
                              newSystem.setIdSystem(dto.id());
                              newSystem.setName(
                                  dto.name() != null ? dto.name() : "System-" + dto.id());
                              return starSystemRepository.save(newSystem);
                            }));

    boolean updated = false;
    if (dto.name() != null && !dto.name().equals(starSystem.getName())) {
      starSystem.setName(dto.name());
      updated = true;
    }

    Boolean isAvailableLive = dto.checkIsAvailableLive();
    if (isAvailableLive != null && !isAvailableLive.equals(starSystem.getIsAvailableLive())) {
      starSystem.setIsAvailableLive(isAvailableLive);
      updated = true;
    }

    if (dto.wiki() != null && !dto.wiki().equals(starSystem.getWiki())) {
      starSystem.setWiki(dto.wiki());
      updated = true;
    }

    if (dto.jurisdictionName() != null
        && !dto.jurisdictionName().equals(starSystem.getJurisdictionName())) {
      starSystem.setJurisdictionName(dto.jurisdictionName());
      updated = true;
    }

    if (dto.factionName() != null && !dto.factionName().equals(starSystem.getFactionName())) {
      starSystem.setFactionName(dto.factionName());
      updated = true;
    }

    if (updated) {
      starSystemRepository.save(starSystem);
    }
  }
}

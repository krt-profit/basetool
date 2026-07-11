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

import de.greluc.krt.profit.basetool.backend.dto.uex.UexRefineryYieldDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexRefiningMethodDto;
import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.RefineryYield;
import de.greluc.krt.profit.basetool.backend.model.RefiningMethod;
import de.greluc.krt.profit.basetool.backend.model.Terminal;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryYieldRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefiningMethodRepository;
import de.greluc.krt.profit.basetool.backend.repository.TerminalRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports the UEX refining-method catalog and the per-terminal refinery yield matrix.
 *
 * <p>The two sync paths are independent and both idempotent. The yields path is the one that has
 * shipped bugs in the past: it must never auto-create placeholder materials or terminals — the
 * commodity catalog and the universe sync are the single sources of truth for those tables. A yield
 * row with an unknown commodity or terminal id is silently skipped (the row gets retried on the
 * next sync once the parent catalog catches up).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UexRefinerySyncService {

  private final UexClient uexClient;
  private final RefiningMethodRepository refiningMethodRepository;
  private final RefineryYieldRepository refineryYieldRepository;
  private final MaterialRepository materialRepository;
  private final TerminalRepository terminalRepository;
  private final AuditService auditService;

  /**
   * Syncs the {@code refining_method} table from UEX. Rows are upserted by name. Empty response or
   * a row with blank name is silently skipped.
   */
  @Transactional
  public void syncRefiningMethods() {
    log.info("Starting sync for Refining Methods...");
    List<UexRefiningMethodDto> dtos = uexClient.getRefineriesMethods();
    if (dtos.isEmpty()) {
      log.warn(
          "No refining methods received from UEX API. Aborting refining method synchronization.");
      return;
    }

    int added = 0;
    int updated = 0;

    for (UexRefiningMethodDto dto : dtos) {
      if (dto.name() == null || dto.name().isBlank()) {
        continue;
      }

      RefiningMethod entity =
          refiningMethodRepository
              .findByName(dto.name())
              .orElseGet(
                  () -> {
                    RefiningMethod n = new RefiningMethod();
                    n.setName(dto.name());
                    return n;
                  });

      final boolean isNew = entity.getId() == null;

      entity.setCode(dto.code());
      entity.setRatingYield(dto.ratingYield());
      entity.setRatingCost(dto.ratingCost());
      entity.setRatingSpeed(dto.ratingSpeed());

      refiningMethodRepository.save(entity);

      if (isNew) {
        added++;
      } else {
        updated++;
      }
    }
    log.info("Finished UEX Refining Methods sync: {} added, {} updated", added, updated);
    // System-actor summary event (one per run, never per row); actor resolves to "system" as no
    // security context exists on the scheduled path.
    auditService.record(
        AuditEventType.REFINERY_METHODS_SYNCED,
        null,
        null,
        null,
        AuditDetails.of("source", "UEX").with("added", added).with("updated", updated));
  }

  /**
   * Syncs the {@code refinery_yield} matrix from UEX. Rows where the commodity or terminal id is
   * unknown locally are silently skipped — the catalog parents own those tables.
   */
  @Transactional
  public void syncRefineryYields() {
    log.info("Starting sync for Refinery Yields...");
    List<UexRefineryYieldDto> dtos = uexClient.getRefineriesYields();
    if (dtos.isEmpty()) {
      log.warn(
          "No refinery yields received from UEX API. Aborting refinery yield synchronization.");
      return;
    }

    int processed = 0;

    for (UexRefineryYieldDto dto : dtos) {
      if (dto.idCommodity() == null || dto.idTerminal() == null || dto.value() == null) {
        continue;
      }

      Material material = materialRepository.findByIdCommodity(dto.idCommodity()).orElse(null);
      Terminal terminal = terminalRepository.findByIdTerminal(dto.idTerminal()).orElse(null);

      if (material == null || terminal == null) {
        continue;
      }

      RefineryYield entity =
          refineryYieldRepository
              .findByTerminalIdAndMaterialId(terminal.getId(), material.getId())
              .orElseGet(
                  () -> {
                    RefineryYield n = new RefineryYield();
                    n.setTerminal(terminal);
                    n.setMaterial(material);
                    return n;
                  });

      entity.setYieldBonus(dto.value());
      refineryYieldRepository.save(entity);
      processed++;
    }
    log.info("Finished UEX Refinery Yields sync: Processed {} yields", processed);
    auditService.record(
        AuditEventType.REFINERY_YIELDS_SYNCED,
        null,
        null,
        null,
        AuditDetails.of("source", "UEX").with("processed", processed));
  }
}

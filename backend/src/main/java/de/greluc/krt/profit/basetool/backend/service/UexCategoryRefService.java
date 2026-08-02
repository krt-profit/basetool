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

import de.greluc.krt.profit.basetool.backend.dto.uex.UexCategoryDto;
import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.model.UexCategory;
import de.greluc.krt.profit.basetool.backend.repository.UexCategoryRepository;
import de.greluc.krt.profit.basetool.backend.support.LogSafe;
import de.greluc.krt.profit.basetool.backend.support.UexValues;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Syncs UEX Corp's {@code /categories} endpoint into the local {@code uex_category} reference
 * table.
 *
 * <p>The UEX categories drive {@link UexItemSyncService}'s walk through {@code
 * /items?id_category=<n>}; this service is its prerequisite and runs once per UEX scheduler tick
 * before the item sync. Only {@code item} / {@code vehicle} categories are persisted — {@code
 * uex_category.type} is constrained to those ({@code chk_uex_category_type}, V109) and the item
 * sync reads only {@code item} rows; UEX's other types (e.g. {@code service}) carry no items and
 * are skipped so one unsupported type can never abort the sweep. Idempotent: matching is by UEX
 * integer id (PK), so a re-run on an unchanged catalogue is a no-op series of {@code SELECT}s
 * followed by no-op {@code UPDATE}s. An empty UEX response short-circuits without wiping local
 * data.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UexCategoryRefService {

  /**
   * Cap for the upstream-supplied category {@code section} / {@code name} in log lines. UEX is a
   * third party we do not control, so both are untrusted free text and go through {@link LogSafe}
   * first; 64 characters comfortably fit any real category label.
   */
  private static final int MAX_LABEL_LOG_LENGTH = 64;

  private final UexClient uexClient;
  private final UexCategoryRepository repository;

  /**
   * Pulls the category catalogue and upserts each row. Returns the persisted list so {@link
   * UexItemSyncService} can iterate without re-querying the DB.
   *
   * @return the persisted {@code item} / {@code vehicle} categories after this sync run
   */
  @Transactional
  public List<UexCategory> syncCategories() {
    log.info("Starting synchronization of UEX categories...");
    UexClient.FetchResult<UexCategoryDto> fetched = uexClient.getCategories();
    if (fetched.notModified()) {
      // A conditional-GET hit: the category table is byte-identical to the last run, so there is
      // nothing to upsert. The persisted rows are still the current truth and are handed to the
      // item sync unchanged — this is a healthy no-op, not the outage the WARN below reports.
      log.info("UEX category catalogue unchanged since the last sync (304) — nothing to import.");
      return repository.findAll();
    }
    List<UexCategoryDto> dtos = fetched.data();
    if (dtos.isEmpty()) {
      log.warn("No categories received from UEX API. Aborting synchronization.");
      return repository.findAll();
    }

    Instant now = Instant.now();
    int added = 0;
    int updated = 0;
    int skipped = 0;
    for (UexCategoryDto dto : dtos) {
      if (dto.id() == null || dto.section() == null || dto.name() == null) {
        log.debug(
            "Skipping category with missing id/section/name (id={}, section='{}', name='{}')",
            dto.id(),
            LogSafe.text(dto.section(), MAX_LABEL_LOG_LENGTH),
            LogSafe.text(dto.name(), MAX_LABEL_LOG_LENGTH));
        continue;
      }
      String type = dto.type() == null ? "item" : dto.type();
      if (!"item".equals(type) && !"vehicle".equals(type)) {
        // uex_category.type is constrained to ('item','vehicle') (chk_uex_category_type, V109), the
        // only types the item sync reads. UEX also returns other types (e.g. 'service') that carry
        // no items; skip them so a single unsupported type never aborts the whole UEX sweep.
        log.debug("Skipping UEX category {} with unsupported type '{}'", dto.id(), type);
        skipped++;
        continue;
      }
      Optional<UexCategory> existingOpt = repository.findById(dto.id());
      UexCategory category = existingOpt.orElseGet(UexCategory::new);
      boolean isNew = existingOpt.isEmpty();
      if (isNew) {
        category.setId(dto.id());
      }
      category.setType(type);
      category.setSection(dto.section());
      category.setName(dto.name());
      category.setIsGameRelated(UexValues.asBooleanOrFalse(dto.isGameRelated()));
      category.setIsMining(UexValues.asBooleanOrFalse(dto.isMining()));
      category.setUexSyncedAt(now);
      category.setUexDeletedAt(null);
      repository.save(category);
      if (isNew) {
        added++;
      } else {
        updated++;
      }
    }

    log.info(
        "Finished UEX category sync: {} added, {} updated, {} skipped (unsupported type)",
        added,
        updated,
        skipped);
    return repository.findAll();
  }
}

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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Manufacturer;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiManufacturerDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.scwiki.ScWikiResponseDto;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
public class ScWikiManufacturerSyncService {

  private final ScWikiClient scWikiClient;
  private final ScWikiProperties properties;
  private final ManufacturerRepository manufacturerRepository;
  private final SyncReportService syncReportService;
  private final SyncChunkWriter chunkWriter;

  /**
   * Reconciles a complete manufacturer census with isolated database writes.
   *
   * @return successfully linked or refreshed manufacturers
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public int syncManufacturers() {
    if (!Boolean.TRUE.equals(properties.manufacturerSyncEnabled())) {
      return 0;
    }
    try (var _ = scWikiClient.openSyncRun()) {
      return reconcileCensus();
    }
  }

  private int reconcileCensus() {
    var result =
        scWikiClient.fetchAllPagesResult(
            properties.manufacturersEndpoint(),
            new ParameterizedTypeReference<ScWikiResponseDto<ScWikiManufacturerDto>>() {},
            "manufacturers");

    List<ScWikiManufacturerDto> rows = result.data();
    if (rows.isEmpty()) {
      syncReportService.recordSweep(false);
      return 0;
    }
    Instant now = Instant.now();
    UUID runId = syncReportService.beginRun();
    Set<UUID> conflicts = new HashSet<>();
    Set<UUID> seen = new HashSet<>();
    List<ScWikiManufacturerDto> valid =
        rows.stream().filter(dto -> dto != null && dto.uuid() != null).toList();
    valid.forEach(dto -> seen.add(dto.uuid()));
    Map<String, Long> names =
        valid.stream()
            .map(ScWikiManufacturerDto::name)
            .filter(StringUtils::hasText)
            .map(ScWikiManufacturerSyncService::sourceKey)
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    Map<String, Long> codes =
        valid.stream()
            .map(ScWikiManufacturerDto::code)
            .filter(StringUtils::hasText)
            .map(ScWikiManufacturerSyncService::sourceKey)
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));
    if (valid.size() != rows.size() || seen.size() != rows.size()) {
      syncReportService.recordIncomplete();
    }
    var outcome =
        chunkWriter.write(
            valid,
            SyncChunkWriter.DEFAULT_CHUNK_SIZE,
            chunk ->
                chunk.stream()
                    .map(dto -> reconcile(dto, runId, now, conflicts, names, codes))
                    .filter(id -> id != null)
                    .toList(),
            "Wiki manufacturer",
            dto -> dto.uuid().toString());
    boolean complete =
        result.complete()
            && valid.size() == rows.size()
            && seen.size() == rows.size()
            && outcome.failedRows() == 0
            && conflicts.isEmpty();
    if (complete && !seen.isEmpty() && !outcome.results().isEmpty()) {
      int marked =
          chunkWriter.inNewTransaction(
              () -> manufacturerRepository.markScwikiDeletedExcept(seen, now));
      syncReportService.recordApplied(marked);
      syncReportService.recordSweep(true);
    } else {
      syncReportService.recordSweep(false);
    }
    syncReportService.pruneRuns(SyncSourceSystem.SCWIKI);
    return outcome.results().size();
  }

  @Nullable
  private UUID reconcile(
      ScWikiManufacturerDto dto,
      UUID runId,
      Instant now,
      Set<UUID> conflicts,
      Map<String, Long> names,
      Map<String, Long> codes) {
    ManufacturerMatch resolution = resolve(dto, names, codes);
    Manufacturer manufacturer = resolution.manufacturer();
    if (manufacturer == null) {
      if (resolution.ambiguous()) {
        conflicts.add(dto.uuid());
        syncReportService.recordIncomplete();
        syncReportService.logScwikiEvent(
            runId,
            SyncEventType.MULTI_MATCH_AMBIGUOUS,
            "manufacturer",
            dto.uuid(),
            dto.name(),
            "Manufacturer source identity is ambiguous.");
      }
      return null;
    }
    UUID link = manufacturer.getScwikiUuid();
    if (link != null && !link.equals(dto.uuid())) {
      syncReportService.logScwikiEvent(
          runId,
          SyncEventType.MANUFACTURER_MISMATCH,
          "manufacturer",
          dto.uuid(),
          dto.name(),
          "Matched manufacturer is linked to another Wiki UUID.");
      syncReportService.recordIncomplete();
      conflicts.add(dto.uuid());
      return null;
    }
    manufacturer.setScwikiUuid(dto.uuid());
    if (StringUtils.hasText(dto.code())) {
      manufacturer.setScwikiCode(dto.code());
    }
    manufacturer.setScwikiSyncedAt(now);
    manufacturer.setScwikiDeletedAt(null);
    manufacturerRepository.save(manufacturer);
    if (link == null) {
      syncReportService.logScwikiEvent(
          runId,
          SyncEventType.MANUFACTURER_LINKED,
          "manufacturer",
          dto.uuid(),
          manufacturer.getName(),
          "Linked Wiki manufacturer.");
    }
    return dto.uuid();
  }

  /**
   * Resolves a Wiki manufacturer to a local row by {@code scwiki_uuid}, then case-insensitive name,
   * then case-insensitive {@code abbreviation == code}.
   *
   * @param dto the Wiki manufacturer payload
   * @param names current Wiki identities per normalized exact name
   * @param codes current Wiki identities per normalized engine code
   * @return the matching local manufacturer, or {@code null}
   */
  private ManufacturerMatch resolve(
      @NotNull ScWikiManufacturerDto dto, Map<String, Long> names, Map<String, Long> codes) {
    Optional<Manufacturer> byUuid = manufacturerRepository.findByScwikiUuid(dto.uuid());
    if (byUuid.isPresent()) {
      return new ManufacturerMatch(byUuid.get(), false);
    }
    boolean ambiguous = false;
    if (StringUtils.hasText(dto.name())) {
      long count = names.getOrDefault(sourceKey(dto.name()), 0L);
      if (count == 1) {
        var byName = manufacturerRepository.findByNameIgnoreCase(dto.name().strip());
        if (byName.isPresent()) {
          return new ManufacturerMatch(byName.get(), false);
        }
      } else {
        ambiguous = true;
      }
    }
    if (StringUtils.hasText(dto.code())) {
      long count = codes.getOrDefault(sourceKey(dto.code()), 0L);
      if (count == 1) {
        List<Manufacturer> byCode =
            manufacturerRepository.findByAbbreviationIgnoreCase(dto.code().strip());
        if (byCode.size() == 1) {
          return new ManufacturerMatch(byCode.getFirst(), false);
        }
        ambiguous |= byCode.size() > 1;
      } else {
        ambiguous = true;
      }
    }
    return new ManufacturerMatch(null, ambiguous);
  }

  /** A local identity candidate with an explicit ambiguity outcome. */
  private record ManufacturerMatch(@Nullable Manufacturer manufacturer, boolean ambiguous) {}

  private static String sourceKey(@NotNull String value) {
    return value.strip().toLowerCase(Locale.ROOT);
  }
}

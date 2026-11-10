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
    SourceKeys keys = new SourceKeys(names, codes, seen);
    var outcome =
        chunkWriter.write(
            valid,
            SyncChunkWriter.DEFAULT_CHUNK_SIZE,
            chunk ->
                chunk.stream()
                    .map(dto -> reconcile(dto, runId, now, conflicts, keys))
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
      ScWikiManufacturerDto dto, UUID runId, Instant now, Set<UUID> conflicts, SourceKeys keys) {
    ManufacturerMatch resolution = resolve(dto, keys);
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
    UUID p4kUuid = manufacturer.getP4kUuid();
    if (p4kUuid != null) {
      syncReportService.logP4kDisagreement(
          SyncSourceSystem.SCWIKI,
          runId,
          "manufacturer",
          p4kUuid,
          "name",
          manufacturer.getName(),
          dto.name());
      syncReportService.logP4kDisagreement(
          SyncSourceSystem.SCWIKI,
          runId,
          "manufacturer",
          p4kUuid,
          "abbreviation",
          manufacturer.getAbbreviation(),
          dto.code());
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
   * Resolves a Wiki manufacturer to a local row by {@code p4k_uuid}, {@code scwiki_uuid}, then
   * case-insensitive name, then case-insensitive {@code abbreviation == code}.
   *
   * <p>A name or code that several census rows share is ambiguous only while a local row carrying
   * it is still unclaimed; a local row linked to another manufacturer of this census belongs to
   * that sibling, and a key no local row carries leaves the Wiki row simply unmatched.
   *
   * @param dto the Wiki manufacturer payload
   * @param keys the census's shared keys and identities
   * @return the matching local manufacturer, or {@code null}
   */
  private ManufacturerMatch resolve(@NotNull ScWikiManufacturerDto dto, SourceKeys keys) {
    Optional<Manufacturer> byUuid =
        manufacturerRepository
            .findByP4kUuid(dto.uuid())
            .or(() -> manufacturerRepository.findByScwikiUuid(dto.uuid()));
    if (byUuid.isPresent()) {
      return new ManufacturerMatch(byUuid.get(), false);
    }
    boolean ambiguous = false;
    if (StringUtils.hasText(dto.name())) {
      var byName = manufacturerRepository.findByNameIgnoreCase(dto.name().strip());
      if (keys.names().getOrDefault(sourceKey(dto.name()), 0L) == 1) {
        if (byName.isPresent()) {
          return new ManufacturerMatch(byName.get(), false);
        }
      } else {
        ambiguous = byName.filter(row -> !keys.heldBySibling(row, dto.uuid())).isPresent();
      }
    }
    if (StringUtils.hasText(dto.code())) {
      List<Manufacturer> byCode =
          manufacturerRepository.findByAbbreviationIgnoreCase(dto.code().strip());
      if (keys.codes().getOrDefault(sourceKey(dto.code()), 0L) == 1) {
        if (byCode.size() == 1) {
          return new ManufacturerMatch(byCode.getFirst(), false);
        }
        ambiguous |= byCode.size() > 1;
      } else {
        ambiguous |= byCode.stream().anyMatch(row -> !keys.heldBySibling(row, dto.uuid()));
      }
    }
    return new ManufacturerMatch(null, ambiguous);
  }

  /** A local identity candidate with an explicit ambiguity outcome. */
  private record ManufacturerMatch(@Nullable Manufacturer manufacturer, boolean ambiguous) {}

  /**
   * One census's key counts and identities, used to tell a shared key apart from a contested one.
   *
   * @param names census rows per normalized exact name
   * @param codes census rows per normalized engine code
   * @param census Wiki UUIDs of every valid census row
   */
  private record SourceKeys(Map<String, Long> names, Map<String, Long> codes, Set<UUID> census) {

    /**
     * Tells whether a local row is already linked to another manufacturer of this census.
     *
     * @param row local manufacturer carrying a shared key
     * @param self Wiki UUID being resolved
     * @return {@code true} when its P4K or Wiki UUID names a different census row
     */
    boolean heldBySibling(Manufacturer row, UUID self) {
      return linkedElsewhere(row.getScwikiUuid(), self) || linkedElsewhere(row.getP4kUuid(), self);
    }

    private boolean linkedElsewhere(@Nullable UUID link, UUID self) {
      return link != null && !link.equals(self) && census.contains(link);
    }
  }

  private static String sourceKey(@NotNull String value) {
    return value.strip().toLowerCase(Locale.ROOT);
  }
}

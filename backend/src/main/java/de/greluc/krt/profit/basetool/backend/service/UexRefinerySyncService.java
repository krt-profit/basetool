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
import de.greluc.krt.profit.basetool.backend.model.RefineryYield;
import de.greluc.krt.profit.basetool.backend.model.RefiningMethod;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryYieldRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefiningMethodRepository;
import de.greluc.krt.profit.basetool.backend.repository.TerminalRepository;
import de.greluc.krt.profit.basetool.backend.support.AuditDetails;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports the UEX refining-method catalog and the per-terminal refinery yield matrix.
 *
 * <p>The two sync paths are independent and both idempotent. The yields path is the one that has
 * shipped bugs in the past: it must never auto-create placeholder materials or terminals — the
 * commodity catalog and the universe sync are the single sources of truth for those tables. A yield
 * row with an unknown commodity or terminal id is silently skipped (the row gets retried on the
 * next sync once the parent catalog catches up).
 *
 * <p><strong>Transactions (BE-PERF-09, REQ-DATA-005).</strong> Both feeds are fetched with no
 * transaction open and written through {@link SyncChunkWriter} — chunks in their own transactions,
 * a failed chunk replayed row by row. The yield matrix resolves the material, the terminal and the
 * existing yield row from three id maps read once per run instead of three lookups per row. The one
 * audit summary per run is written in a transaction of its own, after the rows.
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

  /** Writes the rows in short isolated transactions after the fetch (BE-PERF-09). */
  private final SyncChunkWriter chunkWriter;

  /**
   * Syncs the {@code refining_method} table from UEX. Rows are upserted by name. Empty response or
   * a row with blank name is silently skipped. Holds no transaction across the fetch.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void syncRefiningMethods() {
    log.info("Starting sync for Refining Methods...");
    List<UexRefiningMethodDto> dtos = uexClient.getRefineriesMethods();
    if (dtos.isEmpty()) {
      log.warn(
          "No refining methods received from UEX API. Aborting refining method synchronization.");
      return;
    }

    SyncChunkWriter.Outcome<Boolean> outcome =
        chunkWriter.write(
            dtos,
            SyncChunkWriter.DEFAULT_CHUNK_SIZE,
            chunk -> chunk.stream().map(this::upsertMethod).filter(r -> r != null).toList(),
            "refining method",
            dto -> "(code=" + dto.code() + ")");
    int added = (int) outcome.results().stream().filter(Boolean::booleanValue).count();
    int updated = outcome.results().size() - added;
    log.info("Finished UEX Refining Methods sync: {} added, {} updated", added, updated);
    chunkWriter.inNewTransaction(
        () ->
            auditService.record(
                AuditEventType.REFINERY_METHODS_SYNCED,
                null,
                null,
                null,
                AuditDetails.of("source", "UEX").with("added", added).with("updated", updated)));
  }

  /**
   * Upserts one refining method by name inside the caller's chunk transaction.
   *
   * @param dto the inbound UEX row
   * @return {@code true} when the row was created, {@code false} when it was updated, {@code null}
   *     when it was skipped for a blank name
   */
  @Nullable
  private Boolean upsertMethod(@NotNull UexRefiningMethodDto dto) {
    if (dto.name() == null || dto.name().isBlank()) {
      return null;
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
    return isNew;
  }

  /**
   * Syncs the {@code refinery_yield} matrix from UEX. Rows where the commodity or terminal id is
   * unknown locally are silently skipped — the catalog parents own those tables. Holds no
   * transaction across the fetch.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void syncRefineryYields() {
    log.info("Starting sync for Refinery Yields...");
    List<UexRefineryYieldDto> dtos = uexClient.getRefineriesYields();
    if (dtos.isEmpty()) {
      log.warn(
          "No refinery yields received from UEX API. Aborting refinery yield synchronization.");
      return;
    }

    UexMatrixLookups lookups =
        chunkWriter.inNewTransaction(
            () ->
                new UexMatrixLookups(
                    materialRepository.findUexCommodityRefs(),
                    terminalRepository.findUexTerminalRefs(),
                    refineryYieldRepository.findYieldKeyRefs()));
    SyncChunkWriter.Outcome<UUID> outcome =
        chunkWriter.write(
            dtos,
            SyncChunkWriter.DEFAULT_CHUNK_SIZE,
            chunk -> writeYieldChunk(chunk, lookups),
            "refinery yield",
            dto -> "(idCommodity=" + dto.idCommodity() + ", idTerminal=" + dto.idTerminal() + ")");
    int processed = outcome.results().size();
    log.info("Finished UEX Refinery Yields sync: Processed {} yields", processed);
    chunkWriter.inNewTransaction(
        () ->
            auditService.record(
                AuditEventType.REFINERY_YIELDS_SYNCED,
                null,
                null,
                null,
                AuditDetails.of("source", "UEX").with("processed", processed)));
  }

  /**
   * Writes one chunk of yield rows inside its transaction: the chunk's existing rows load with one
   * {@code findAllById}, the rest are created against {@code getReferenceById} parents. A row with
   * a missing id or value, or an unknown commodity or terminal, is skipped — never a placeholder
   * parent.
   *
   * @param chunk the rows of this chunk
   * @param lookups the preloaded id maps, extended with every row written
   * @return the ids of the yield rows written, one per row processed
   */
  @NotNull
  private List<UUID> writeYieldChunk(
      @NotNull List<UexRefineryYieldDto> chunk, @NotNull UexMatrixLookups lookups) {
    List<UUID> existingIds = new ArrayList<>();
    for (UexRefineryYieldDto dto : chunk) {
      UUID materialId = lookups.parentId(dto.idCommodity());
      UUID terminalId = lookups.terminalId(dto.idTerminal());
      UUID yieldId =
          materialId == null || terminalId == null ? null : lookups.rowId(materialId, terminalId);
      if (yieldId != null) {
        existingIds.add(yieldId);
      }
    }
    Map<UUID, RefineryYield> existing =
        existingIds.isEmpty()
            ? Map.of()
            : refineryYieldRepository.findAllById(existingIds).stream()
                .collect(Collectors.toMap(RefineryYield::getId, Function.identity()));
    Map<UexMatrixLookups.Pair, RefineryYield> written = new LinkedHashMap<>();
    List<UexMatrixLookups.Pair> rowKeys = new ArrayList<>();
    for (UexRefineryYieldDto dto : chunk) {
      if (dto.idCommodity() == null || dto.idTerminal() == null || dto.value() == null) {
        continue;
      }
      UUID materialId = lookups.parentId(dto.idCommodity());
      UUID terminalId = lookups.terminalId(dto.idTerminal());
      if (materialId == null || terminalId == null) {
        continue;
      }
      UexMatrixLookups.Pair key = new UexMatrixLookups.Pair(materialId, terminalId);
      RefineryYield entity = written.get(key);
      if (entity == null) {
        UUID yieldId = lookups.rowId(materialId, terminalId);
        entity = yieldId == null ? null : existing.get(yieldId);
      }
      if (entity == null) {
        entity = new RefineryYield();
        entity.setTerminal(terminalRepository.getReferenceById(terminalId));
        entity.setMaterial(materialRepository.getReferenceById(materialId));
      }
      entity.setYieldBonus(dto.value());
      written.put(key, entity);
      rowKeys.add(key);
    }
    Map<UexMatrixLookups.Pair, UUID> savedIds = new LinkedHashMap<>();
    for (Map.Entry<UexMatrixLookups.Pair, RefineryYield> entry : written.entrySet()) {
      UUID id = refineryYieldRepository.save(entry.getValue()).getId();
      lookups.rememberRow(entry.getKey().parentId(), entry.getKey().terminalId(), id);
      savedIds.put(entry.getKey(), id);
    }
    return rowKeys.stream().map(savedIds::get).toList();
  }
}

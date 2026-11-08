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

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialPrice;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexCommodityDto;
import de.greluc.krt.profit.basetool.backend.catalogue.internal.uex.UexCommodityPriceDto;
import de.greluc.krt.profit.basetool.logging.LogSafe;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Imports the UEX commodity catalogue into {@code material} and the commodity-price matrix into
 * {@code material_price} (REQ-DATA-005).
 *
 * <p>Materials match by UEX {@code id_commodity}, then by name; materials the matrix names but the
 * catalogue lacks are created, unknown terminals are skipped. Rows are written through {@link
 * SyncChunkWriter} with no transaction held across HTTP calls. Price rows UEX no longer lists are
 * cleared via {@link MaterialPriceRepository#clearPricesByIds} unless nothing was written; an empty
 * or unchanged feed leaves local data untouched. A non-empty catalogue also replaces each listed
 * material's locations through {@link UexCommodityOccurrenceSync} (REQ-DATA-031). UEX never renames
 * a material: a UEX name that differs from the name of a row the P4K matched is reported to {@code
 * external_sync_report} (REQ-P4K-024).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UexCommodityService {

  /** Maximum length of an upstream commodity name in log lines, logged through {@link LogSafe}. */
  private static final int MAX_NAME_LOG_LENGTH = 64;

  private final UexClient uexClient;
  private final MaterialRepository materialRepository;
  private final MaterialPriceRepository materialPriceRepository;
  private final TerminalRepository terminalRepository;

  /** Replaces each material's UEX location set after the catalogue upsert (REQ-DATA-031). */
  private final UexCommodityOccurrenceSync occurrenceSync;

  /** Writes the rows in short isolated transactions after the fetch. */
  private final SyncChunkWriter chunkWriter;

  /** Records the name disagreements with the P4K. */
  private final SyncReportService syncReportService;

  /**
   * Runs the full commodity + price sync (see class Javadoc). Deliberately holds no transaction:
   * each fetch runs with none open, and each chunk of rows commits on its own.
   */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void fetchAndProcessCommoditiesPrices() {
    log.info("Starting synchronization of UEX commodities...");
    UexClient.FetchResult<UexCommodityDto> commodityFetch = uexClient.getCommodities();
    List<UexCommodityDto> commodities = commodityFetch.data();

    if (!commodities.isEmpty()) {
      UUID runId = syncReportService.beginRun();
      SyncChunkWriter.Outcome<Integer> outcome =
          chunkWriter.write(
              commodities,
              SyncChunkWriter.DEFAULT_CHUNK_SIZE,
              chunk ->
                  chunk.stream()
                      .map(dto -> upsertCommodity(dto, runId))
                      .filter(id -> id != null)
                      .toList(),
              "commodity",
              dto ->
                  "(id="
                      + dto.id()
                      + ", name='"
                      + LogSafe.text(dto.name(), MAX_NAME_LOG_LENGTH)
                      + "')");
      log.info(
          "Finished commodities synchronization. Processed {} items ({} failed).",
          outcome.results().size(),
          outcome.failedRows());
      if (commodityFetch.complete()
          && outcome.failedRows() == 0
          && outcome.results().size() == commodities.size()) {
        occurrenceSync.replaceAll(commodities);
        chunkWriter.recordSweep(true);
      } else {
        chunkWriter.recordSweep(false);
        log.warn("Skipping commodity location replacement after an incomplete fetch or write");
      }
    } else {
      chunkWriter.recordSweep(false);
      log.warn("No commodities received from UEX API.");
    }

    log.info("Starting synchronization of UEX commodity prices...");
    UexClient.FetchResult<UexCommodityPriceDto> priceFetch = uexClient.getCommoditiesPricesAll();
    List<UexCommodityPriceDto> dtos = priceFetch.data();

    if (dtos.isEmpty()) {
      chunkWriter.recordSweep(false);
      log.warn("No data received from UEX API. Aborting synchronization.");
      return;
    }

    List<UexCommodityPriceDto> usable = new ArrayList<>();
    for (UexCommodityPriceDto dto : dtos) {
      if (dto.idCommodity() == null || dto.idTerminal() == null) {
        log.warn(
            "Missing commodity or terminal ID in price DTO (idCommodity={}, idTerminal={},"
                + " name='{}')",
            dto.idCommodity(),
            dto.idTerminal(),
            LogSafe.text(dto.commodityName(), MAX_NAME_LOG_LENGTH));
      } else {
        usable.add(dto);
      }
    }
    chunkWriter.recordSkipped(dtos.size() - usable.size());

    UexMatrixLookups lookups = chunkWriter.inNewTransaction(this::loadPriceLookups);
    createMissingMaterials(usable, lookups);

    SyncChunkWriter.Outcome<UUID> outcome =
        chunkWriter.write(
            usable,
            SyncChunkWriter.DEFAULT_CHUNK_SIZE,
            chunk -> writePriceChunk(chunk, lookups),
            "commodity price",
            dto ->
                "(idCommodity="
                    + dto.idCommodity()
                    + ", idTerminal="
                    + dto.idTerminal()
                    + ", name='"
                    + LogSafe.text(dto.commodityName(), MAX_NAME_LOG_LENGTH)
                    + "')");
    Set<UUID> seenPriceIds = new HashSet<>(outcome.results());

    boolean cleanupAllowed =
        priceFetch.complete()
            && outcome.failedRows() == 0
            && usable.size() == dtos.size()
            && everyPriceReferenceResolves(usable, lookups);
    if (seenPriceIds.isEmpty() || !cleanupAllowed) {
      chunkWriter.recordSweep(false);
      log.warn(
          "Skipping stale-row cleanup because the commodity-price census or its application"
              + " is incomplete ({} dto(s) received).",
          dtos.size());
    } else {
      int cleared =
          chunkWriter.inNewTransaction(() ->
              StalePriceSweep.clearStale(
                  materialPriceRepository.findIdsWithLivePrices(),
                  seenPriceIds,
                  materialPriceRepository::clearPricesByIds));
      chunkWriter.recordApplied(cleared);
      chunkWriter.recordSweep(true);
      if (cleared > 0) {
        log.info("Cleared prices on {} material_price row(s) no longer returned by UEX.", cleared);
      }
    }

    log.info(
        "Finished synchronization. Processed {} items ({} failed).",
        outcome.results().size(),
        outcome.failedRows());
  }

  private static boolean everyPriceReferenceResolves(
      List<UexCommodityPriceDto> rows, UexMatrixLookups lookups) {
    return rows.stream()
        .allMatch(row ->
            lookups.parentId(row.idCommodity()) != null
                && lookups.terminalId(row.idTerminal()) != null);
  }

  /**
   * Upserts one catalogue row inside the caller's chunk transaction: resolved by {@code
   * id_commodity}, then adopted by name (flipping a manual or Wiki-only row's provenance), else
   * created.
   *
   * @param dto the inbound UEX commodity row
   * @param runId the sync run, for the report of a name the P4K leads
   * @return the row's UEX commodity id once saved, or {@code null} when the row lacks an id or a
   *     name
   */
  @Nullable
  private Integer upsertCommodity(@NotNull UexCommodityDto dto, @NotNull UUID runId) {
    if (dto.id() == null || dto.name() == null) {
      return null;
    }
    Material material =
        materialRepository
            .findByIdCommodity(dto.id())
            .orElseGet(() ->
                materialRepository
                    .findByName(dto.name())
                    .map(m -> {
                      m.setIdCommodity(dto.id());
                      if (m.getSourceSystems() == MaterialSourceSystem.MANUAL) {
                        log.info(
                            "Manual material '{}' is now linked to UEX commodity id={}",
                            m.getName(),
                            dto.id());
                        m.setSourceSystems(MaterialSourceSystem.UEX_ONLY);
                      }
                      promoteOnUexAdoption(m);
                      return m;
                    })
                    .orElseGet(() -> {
                      Material newMaterial = new Material();
                      newMaterial.setIdCommodity(dto.id());
                      newMaterial.setName(dto.name());
                      return newMaterial;
                    }));

    reportNameDisagreement(material, dto, runId);
    material.setType(determineMaterialType(dto));
    material.setCode(dto.code());
    material.setKind(dto.kind());
    material.setWeightScu(dto.weightScu());
    material.setPriceBuy(dto.priceBuy());
    material.setPriceSell(dto.priceSell());
    material.setIsAvailable(dto.isAvailable());
    material.setIsAvailableLive(dto.isAvailableLive());
    material.setIsExtractable(dto.isExtractable());
    material.setIsMineral(dto.isMineral());
    material.setIsRaw(dto.isRaw());
    material.setIsPure(dto.isPure());
    material.setIsRefined(dto.isRefined());
    material.setIsRefinable(dto.isRefinable());
    material.setIsHarvestable(dto.isHarvestable());
    material.setIsBuyable(dto.isBuyable());
    material.setIsSellable(dto.isSellable());
    material.setIsTemporary(dto.isTemporary());
    material.setIsIllegal(dto.isIllegal());
    material.setIsVolatileQt(dto.isVolatileQt());
    material.setIsVolatileTime(dto.isVolatileTime());
    material.setIsInert(dto.isInert());
    material.setIsExplosive(dto.isExplosive());
    material.setIsBuggy(dto.isBuggy());
    material.setIsFuel(dto.isFuel());
    materialRepository.save(material);
    return dto.id();
  }

  /**
   * Reads the three id maps the price phase resolves every row against — one query each: material
   * id by {@code id_commodity}, terminal id by {@code id_terminal}, and existing price id by
   * (material, terminal).
   *
   * @return the lookups, holding ids only
   */
  @NotNull
  private UexMatrixLookups loadPriceLookups() {
    return new UexMatrixLookups(
        materialRepository.findUexCommodityRefs(),
        terminalRepository.findUexTerminalRefs(),
        materialPriceRepository.findPriceKeyRefs());
  }

  /**
   * Creates (or adopts by name) the materials the price matrix references but the catalogue does
   * not carry yet, so the price-matrix sync stays self-healing when UEX adds a commodity between
   * two runs. Each distinct unknown commodity is resolved in the writer's isolated transactions and
   * its id added to {@code lookups}.
   *
   * @param dtos the usable price rows
   * @param lookups the preloaded lookups, extended in place
   */
  private void createMissingMaterials(
      @NotNull List<UexCommodityPriceDto> dtos, @NotNull UexMatrixLookups lookups) {
    Map<Integer, UexCommodityPriceDto> missing = new LinkedHashMap<>();
    for (UexCommodityPriceDto dto : dtos) {
      if (lookups.parentId(dto.idCommodity()) == null) {
        missing.putIfAbsent(dto.idCommodity(), dto);
      }
    }
    if (missing.isEmpty()) {
      return;
    }
    SyncChunkWriter.Outcome<Map.Entry<Integer, UUID>> created =
        chunkWriter.write(
            List.copyOf(missing.values()),
            SyncChunkWriter.DEFAULT_CHUNK_SIZE,
            chunk ->
                chunk.stream()
                    .map(dto -> Map.entry(dto.idCommodity(), resolveOrCreateMaterial(dto)))
                    .toList(),
            "price-matrix material",
            dto -> "(idCommodity=" + dto.idCommodity() + ")");
    created.results().forEach(e -> lookups.rememberParent(e.getKey(), e.getValue()));
  }

  /**
   * Writes one chunk of price rows inside its transaction, updating existing rows and creating the
   * missing ones; rows with an unknown terminal or material are skipped.
   *
   * <p>Created pairs are recorded in {@code lookups}, so later chunks and replays update them
   * instead of inserting duplicates.
   *
   * @param chunk the price rows of this chunk
   * @param lookups the preloaded id maps
   * @return the ids of the price rows written
   */
  @NotNull
  private List<UUID> writePriceChunk(
      @NotNull List<UexCommodityPriceDto> chunk, @NotNull UexMatrixLookups lookups) {
    List<UUID> existingIds = new ArrayList<>();
    for (UexCommodityPriceDto dto : chunk) {
      UUID materialId = lookups.parentId(dto.idCommodity());
      UUID terminalId = lookups.terminalId(dto.idTerminal());
      UUID priceId =
          materialId == null || terminalId == null ? null : lookups.rowId(materialId, terminalId);
      if (priceId != null) {
        existingIds.add(priceId);
      }
    }
    Map<UUID, MaterialPrice> existing =
        existingIds.isEmpty()
            ? Map.of()
            : materialPriceRepository.findAllById(existingIds).stream()
                .collect(Collectors.toMap(MaterialPrice::getId, Function.identity()));
    Map<UexMatrixLookups.Pair, MaterialPrice> written = new LinkedHashMap<>();
    for (UexCommodityPriceDto dto : chunk) {
      UUID materialId = lookups.parentId(dto.idCommodity());
      UUID terminalId = lookups.terminalId(dto.idTerminal());
      if (materialId == null || terminalId == null) {
        continue;
      }
      UexMatrixLookups.Pair key = new UexMatrixLookups.Pair(materialId, terminalId);
      MaterialPrice price = written.get(key);
      if (price == null) {
        UUID priceId = lookups.rowId(materialId, terminalId);
        price = priceId == null ? null : existing.get(priceId);
      }
      if (price == null) {
        price = new MaterialPrice();
        price.setMaterial(materialRepository.getReferenceById(materialId));
        price.setTerminal(terminalRepository.getReferenceById(terminalId));
      }
      price.setPriceBuy(dto.priceBuy());
      price.setPriceSell(dto.priceSell());
      price.setScuBuy(dto.scuBuy());
      price.setScuSell(dto.scuSell());
      price.setScuSellStock(dto.scuSellStock());
      price.setPriceBuyAvg(dto.priceBuyAvg());
      price.setPriceSellAvg(dto.priceSellAvg());
      price.setScuBuyAvg(dto.scuBuyAvg());
      price.setScuSellAvg(dto.scuSellAvg());
      price.setScuSellStockAvg(dto.scuSellStockAvg());
      price.setContainerSizes(UexValues.parseContainerSizes(dto.containerSizes()));
      price.setStatusBuy(dto.isStatusBuy());
      price.setStatusSell(dto.isStatusSell());
      price.setDateModified(dto.getParsedDateModified());
      written.put(key, price);
    }
    List<UUID> ids = new ArrayList<>();
    for (Map.Entry<UexMatrixLookups.Pair, MaterialPrice> entry : written.entrySet()) {
      UUID id = materialPriceRepository.save(entry.getValue()).getId();
      lookups.rememberRow(entry.getKey().parentId(), entry.getKey().terminalId(), id);
      ids.add(id);
    }
    return ids;
  }

  /**
   * Resolves the material a price row names by {@code id_commodity}, adopts one by name, or creates
   * a placeholder with a fallback name — the price phase's self-healing path, run in the writer's
   * transaction.
   *
   * @param dto the price row naming the commodity
   * @return the resolved or created material's id
   */
  @NotNull
  private UUID resolveOrCreateMaterial(@NotNull UexCommodityPriceDto dto) {
    return materialRepository
        .findByIdCommodity(dto.idCommodity())
        .orElseGet(() ->
            materialRepository
                .findByName(dto.commodityName())
                .map(m -> {
                  m.setIdCommodity(dto.idCommodity());
                  promoteOnUexAdoption(m);
                  return materialRepository.save(m);
                })
                .orElseGet(() -> {
                  Material newMaterial = new Material();
                  newMaterial.setIdCommodity(dto.idCommodity());
                  newMaterial.setName(
                      dto.commodityName() != null
                          ? dto.commodityName()
                          : "Unknown-" + dto.idCommodity());
                  newMaterial.setType(MaterialType.NO_REFINE);
                  return materialRepository.save(newMaterial);
                }))
        .getId();
  }

  /**
   * Reports a UEX name that differs from the name of a row the P4K matched; the P4K name stays.
   *
   * @param material the resolved material
   * @param dto the inbound UEX commodity row
   * @param runId the sync run
   */
  private void reportNameDisagreement(
      @NotNull Material material, @NotNull UexCommodityDto dto, @NotNull UUID runId) {
    UUID p4kUuid = material.getP4kUuid();
    if (p4kUuid != null) {
      syncReportService.logP4kDisagreement(
          SyncSourceSystem.UEX,
          runId,
          "commodity",
          p4kUuid,
          "name",
          material.getName(),
          dto.name());
    }
  }

  @NotNull
  private MaterialType determineMaterialType(UexCommodityDto dto) {
    if (Integer.valueOf(1).equals(dto.isRefined())) {
      return MaterialType.REFINED;
    } else if (Integer.valueOf(1).equals(dto.isRefinable())) {
      return MaterialType.RAW;
    }
    return MaterialType.NO_REFINE;
  }

  /**
   * Flips a {@link MaterialSourceSystem#WIKI_ONLY} material that UEX just adopted by name to {@link
   * MaterialSourceSystem#BOTH}, making it visible in trading flows; any other row is left
   * unchanged.
   *
   * @param material the locally-resolved material UEX just linked by name
   */
  private static void promoteOnUexAdoption(Material material) {
    if (material.getSourceSystems() == MaterialSourceSystem.WIKI_ONLY) {
      material.setSourceSystems(MaterialSourceSystem.BOTH);
      material.setIsVisible(true);
    }
  }
}

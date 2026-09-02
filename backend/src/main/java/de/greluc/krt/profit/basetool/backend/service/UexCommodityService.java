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

import de.greluc.krt.profit.basetool.backend.dto.uex.UexCommodityDto;
import de.greluc.krt.profit.basetool.backend.dto.uex.UexCommodityPriceDto;
import de.greluc.krt.profit.basetool.backend.integration.UexClient;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialPrice;
import de.greluc.krt.profit.basetool.backend.model.MaterialSourceSystem;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.Terminal;
import de.greluc.krt.profit.basetool.backend.repository.MaterialPriceRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.TerminalRepository;
import de.greluc.krt.profit.basetool.backend.support.LogSafe;
import de.greluc.krt.profit.basetool.backend.support.StalePriceSweep;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Imports the UEX commodity catalog and the full commodity-price matrix.
 *
 * <p>Two-phase sync: first the commodity catalog ({@link UexClient#getCommodities()}) is upserted
 * into {@code material} (matched by UEX {@code id_commodity}, falling back to name — legacy
 * migrations may have a record without an id), then the price matrix ({@link
 * UexClient#getCommoditiesPricesAll()}) is upserted into {@code material_price} per (material,
 * terminal) pair. Unknown terminals are silently skipped (the universe sync owns the terminal
 * table); unknown materials get auto-created with a fallback name so the price-matrix sync stays
 * self-healing if UEX adds a commodity between two of our runs.
 *
 * <p>The price-matrix phase additionally records the ids of every row touched and, once the loop
 * completes, nulls out the price / SCU / status columns on every other {@code material_price} row
 * via {@link MaterialPriceRepository#clearStalePrices}. UEX does not signal removals - a terminal
 * that stops listing a commodity simply disappears from the matrix - so without this sweep a stale
 * {@code priceBuy} would survive every subsequent sync. The sweep is gated on a non-empty
 * touched-set so a sync that fails on every single row never wipes the entire table.
 *
 * <p>An empty response on either call short-circuits without wiping local data — the sync is
 * idempotent and resilient to transient UEX outages. An <em>unchanged</em> feed ({@code 304 Not
 * Modified}) short-circuits the same way but is reported at INFO rather than WARN: nothing to
 * re-import is the healthy steady state, not an outage.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UexCommodityService {

  /**
   * Cap for the upstream-supplied commodity name in log lines. UEX is a third party we do not
   * control, so the value is untrusted free text and goes through {@link LogSafe} first; 64
   * characters comfortably fit any real commodity name.
   */
  private static final int MAX_NAME_LOG_LENGTH = 64;

  private final UexClient uexClient;
  private final MaterialRepository materialRepository;
  private final MaterialPriceRepository materialPriceRepository;
  private final TerminalRepository terminalRepository;

  /**
   * Runs the full commodity + price sync (see class Javadoc). Both phases run in the same
   * transaction so a single failed price update never leaves the catalog half-updated relative to
   * the price matrix.
   */
  @Transactional
  public void fetchAndProcessCommoditiesPrices() {
    log.info("Starting synchronization of UEX commodities...");
    UexClient.FetchResult<UexCommodityDto> commodityFetch = uexClient.getCommodities();
    List<UexCommodityDto> commodities = commodityFetch.data();

    if (commodityFetch.notModified()) {
      log.info("UEX commodity catalogue unchanged since the last sync (304) — nothing to import.");
    } else if (!commodities.isEmpty()) {
      int commoditiesProcessed = 0;
      for (UexCommodityDto dto : commodities) {
        try {
          if (dto.id() != null && dto.name() != null) {
            Material material =
                materialRepository
                    .findByIdCommodity(dto.id())
                    .orElseGet(
                        () ->
                            materialRepository
                                .findByName(dto.name())
                                .map(
                                    m -> {
                                      m.setIdCommodity(dto.id());
                                      // A manual entry has just been adopted by UEX. Flip its
                                      // provenance off MANUAL so the admin badge disappears; the
                                      // link to UEX is recorded via the INFO log and the
                                      // now-populated idCommodity column.
                                      if (m.getSourceSystems() == MaterialSourceSystem.MANUAL) {
                                        log.info(
                                            "Manual material '{}' is now linked to UEX commodity"
                                                + " id={}",
                                            m.getName(),
                                            dto.id());
                                        m.setSourceSystems(MaterialSourceSystem.UEX_ONLY);
                                      }
                                      promoteOnUexAdoption(m);
                                      return m;
                                    })
                                .orElseGet(
                                    () -> {
                                      Material newMaterial = new Material();
                                      newMaterial.setIdCommodity(dto.id());
                                      newMaterial.setName(dto.name());
                                      return newMaterial;
                                    }));

            material.setType(determineMaterialType(dto));
            material.setCode(dto.code());
            // No slug: UEX's /commodities payload has no `slug` field, so the previous mapping only
            // wrote null into material.slug on every run (REQ-DATA-015). The commodity's Wiki slug
            // lives in its own column (scwiki_slug) and is written by the Wiki commodity sync.
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

            commoditiesProcessed++;
          }
        } catch (Exception e) {
          log.error(
              "Failed to process commodity dto (id={}, name='{}')",
              dto.id(),
              LogSafe.text(dto.name(), MAX_NAME_LOG_LENGTH),
              e);
        }
      }
      log.info("Finished commodities synchronization. Processed {} items.", commoditiesProcessed);
    } else {
      log.warn("No commodities received from UEX API.");
    }

    log.info("Starting synchronization of UEX commodity prices...");
    UexClient.FetchResult<UexCommodityPriceDto> priceFetch = uexClient.getCommoditiesPricesAll();
    if (priceFetch.notModified()) {
      // Price matrix byte-identical to the last run: nothing to upsert, and — critically — no
      // stale-row sweep either, since every row we would have "seen" is still current.
      log.info(
          "UEX commodity price matrix unchanged since the last sync (304) — nothing to import.");
      return;
    }
    List<UexCommodityPriceDto> dtos = priceFetch.data();

    if (dtos.isEmpty()) {
      log.warn("No data received from UEX API. Aborting synchronization.");
      return;
    }

    Set<UUID> seenPriceIds = new HashSet<>();
    int processed = 0;
    for (UexCommodityPriceDto dto : dtos) {
      try {
        UUID savedId = processSingleDto(dto);
        if (savedId != null) {
          seenPriceIds.add(savedId);
        }
        processed++;
      } catch (Exception e) {
        log.error(
            "Failed to process commodity-price dto (idCommodity={}, idTerminal={}, name='{}')",
            dto.idCommodity(),
            dto.idTerminal(),
            LogSafe.text(dto.commodityName(), MAX_NAME_LOG_LENGTH),
            e);
      }
    }

    if (seenPriceIds.isEmpty()) {
      log.warn(
          "Skipping stale-row cleanup because no commodity-price row could be processed "
              + "({} dto(s) received, all failed). Refusing to wipe the entire price matrix.",
          dtos.size());
    } else {
      // Same bounded-chunk sweep as the item-price matrix (REQ-DATA-014): the parameter count no
      // longer scales with the number of rows the feed returned.
      int cleared =
          StalePriceSweep.clearStale(
              materialPriceRepository.findIdsWithLivePrices(),
              seenPriceIds,
              materialPriceRepository::clearPricesByIds);
      if (cleared > 0) {
        log.info("Cleared prices on {} material_price row(s) no longer returned by UEX.", cleared);
      }
    }

    log.info("Finished synchronization. Processed {} items.", processed);
  }

  private UUID processSingleDto(UexCommodityPriceDto dto) {
    if (dto.idCommodity() == null || dto.idTerminal() == null) {
      log.warn(
          "Missing commodity or terminal ID in price DTO (idCommodity={}, idTerminal={},"
              + " name='{}')",
          dto.idCommodity(),
          dto.idTerminal(),
          LogSafe.text(dto.commodityName(), MAX_NAME_LOG_LENGTH));
      return null;
    }

    Material material = resolveOrCreateMaterial(dto);
    Optional<Terminal> terminalOpt = terminalRepository.findByIdTerminal(dto.idTerminal());

    if (terminalOpt.isEmpty()) {
      return null;
    }
    Terminal terminal = terminalOpt.orElseThrow();

    Optional<MaterialPrice> priceOpt =
        materialPriceRepository.findByMaterialIdAndTerminalId(material.getId(), terminal.getId());
    MaterialPrice price =
        priceOpt.orElseGet(
            () -> {
              MaterialPrice newPrice = new MaterialPrice();
              newPrice.setMaterial(material);
              newPrice.setTerminal(terminal);
              return newPrice;
            });

    price.setPriceBuy(dto.priceBuy());
    price.setPriceSell(dto.priceSell());
    price.setScuBuy(dto.scuBuy());
    price.setScuSell(dto.scuSell());
    price.setScuSellStock(dto.scuSellStock());
    price.setStatusBuy(dto.isStatusBuy());
    price.setStatusSell(dto.isStatusSell());
    price.setDateModified(dto.getParsedDateModified());

    return materialPriceRepository.save(price).getId();
  }

  private Material resolveOrCreateMaterial(UexCommodityPriceDto dto) {
    return materialRepository
        .findByIdCommodity(dto.idCommodity())
        .orElseGet(
            () ->
                materialRepository
                    .findByName(dto.commodityName())
                    .map(
                        m -> {
                          m.setIdCommodity(dto.idCommodity());
                          promoteOnUexAdoption(m);
                          return materialRepository.save(m);
                        })
                    .orElseGet(
                        () -> {
                          Material newMaterial = new Material();
                          newMaterial.setIdCommodity(dto.idCommodity());
                          newMaterial.setName(
                              dto.commodityName() != null
                                  ? dto.commodityName()
                                  : "Unknown-" + dto.idCommodity());
                          newMaterial.setType(MaterialType.NO_REFINE); // Default type
                          return materialRepository.save(newMaterial);
                        }));
  }

  private MaterialType determineMaterialType(UexCommodityDto dto) {
    if (Integer.valueOf(1).equals(dto.isRefined())) {
      return MaterialType.REFINED;
    } else if (Integer.valueOf(1).equals(dto.isRefinable())) {
      return MaterialType.RAW;
    }
    return MaterialType.NO_REFINE;
  }

  /**
   * Promotes a locally-existing material that this UEX sync has just adopted by name-match. A row
   * the Wiki imported first ({@link MaterialSourceSystem#WIKI_ONLY}, inserted invisible per §4.3)
   * is validated by UEX's presence — UEX only carries real trade commodities — so its provenance
   * flips to {@link MaterialSourceSystem#BOTH} and it becomes visible in trading flows. This
   * honours the {@link MaterialSourceSystem} contract (§6.1) that the UEX item and vehicle syncs
   * already follow; the commodity sync previously left an adopted Wiki row stuck at {@code
   * WIKI_ONLY} (and hidden). Idempotent: only a {@code WIKI_ONLY} row is touched, so a normal
   * re-sync of a {@code UEX_ONLY} / {@code BOTH} / {@code MANUAL} row is unaffected <em>by this
   * method</em> — note that the caller has already flipped an adopted {@code MANUAL} row to {@code
   * UEX_ONLY} two statements earlier, so no {@code MANUAL} row ever reaches here on adoption.
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

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

import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialPrice;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.dto.ProfitCalculationDto;
import de.greluc.krt.profit.basetool.backend.repository.MaterialPriceRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the best per-material buy/sell margin given a chosen ship and an optional star-system
 * filter.
 *
 * <p>Drives the profit-calculation page: for each material, finds the lowest auto-load buy price
 * and the highest auto-load sell price across the (filtered) terminal set, then derives the per-SCU
 * profit, the margin percent, and the full-load profit using the ship's SCU capacity. "Hull
 * C"-class ships only get terminals with a loading dock — the in-universe rule that a Hull C cannot
 * land planet-side.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfitCalculationService {

  private final MaterialPriceRepository materialPriceRepository;
  private final ShipTypeRepository shipTypeRepository;

  /**
   * Calculates the profit table for one ship across the chosen star systems.
   *
   * <p>Materials without both a positive buy and a positive sell price are dropped. Hull-C
   * filtering happens in memory after the price fetch so the SQL stays general-purpose. The result
   * is sorted alphabetically by material name so the on-screen order is stable across reloads.
   *
   * @param shipId chosen ship type's id; supplies the SCU capacity
   * @param starSystemNames optional star-system filter; null/empty means "all auto-load terminals"
   * @return profit rows, one per material with both buy and sell sides; alphabetically sorted
   * @throws IllegalArgumentException when {@code shipId} does not resolve to a ship type
   */
  @NotNull
  public List<ProfitCalculationDto> calculateProfit(UUID shipId, List<String> starSystemNames) {
    log.debug("Calculating profit for shipId: {} and starSystems: {}", shipId, starSystemNames);

    ShipType ship =
        shipTypeRepository
            .findById(shipId)
            .orElseThrow(() -> new IllegalArgumentException("ShipType not found: " + shipId));

    int shipScu = ship.getScu() != null ? ship.getScu() : 0;
    boolean isHullC = ship.getName() != null && ship.getName().toLowerCase().contains("hull c");

    log.debug("Ship: {}, SCU: {}, isHullC: {}", ship.getName(), shipScu, isHullC);

    List<MaterialPrice> prices;
    if (starSystemNames == null || starSystemNames.isEmpty()) {
      prices = materialPriceRepository.findAllAutoLoadPrices();
    } else {
      prices = materialPriceRepository.findAllAutoLoadPricesInSystems(starSystemNames);
    }

    log.debug("Found {} potential prices before Hull C filtering", prices.size());

    if (isHullC) {
      prices =
          prices.stream()
              .filter(
                  p ->
                      p.getTerminal().getHasLoadingDock() != null
                          && p.getTerminal().getHasLoadingDock())
              .toList();
      log.debug("Hull C filtering reduced prices to {}", prices.size());
    }

    Map<Material, List<MaterialPrice>> pricesByMaterial =
        prices.stream().collect(Collectors.groupingBy(MaterialPrice::getMaterial));

    List<ProfitCalculationDto> results = new ArrayList<>();

    for (Map.Entry<Material, List<MaterialPrice>> entry : pricesByMaterial.entrySet()) {
      Material material = entry.getKey();
      List<MaterialPrice> materialPrices = entry.getValue();

      BigDecimal minBuy =
          materialPrices.stream()
              .map(MaterialPrice::getPriceBuy)
              .filter(Objects::nonNull)
              .filter(p -> p.compareTo(BigDecimal.ZERO) > 0)
              .min(BigDecimal::compareTo)
              .orElse(null);

      BigDecimal maxSell =
          materialPrices.stream()
              .map(MaterialPrice::getPriceSell)
              .filter(Objects::nonNull)
              .filter(p -> p.compareTo(BigDecimal.ZERO) > 0)
              .max(BigDecimal::compareTo)
              .orElse(null);

      if (minBuy != null && maxSell != null) {
        BigDecimal profitPerScu = maxSell.subtract(minBuy);
        BigDecimal marginPercent =
            minBuy.compareTo(BigDecimal.ZERO) > 0
                ? profitPerScu
                    .multiply(BigDecimal.valueOf(100))
                    .divide(minBuy, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal fullLoadCost = minBuy.multiply(BigDecimal.valueOf(shipScu));
        BigDecimal maxProfitFullLoad = profitPerScu.multiply(BigDecimal.valueOf(shipScu));

        results.add(
            new ProfitCalculationDto(
                material.getId(),
                material.getName(),
                minBuy,
                maxSell,
                profitPerScu,
                marginPercent,
                fullLoadCost,
                maxProfitFullLoad));
      }
    }

    results.sort(Comparator.comparing(ProfitCalculationDto::materialName));
    log.debug("Calculation finished, returned {} results", results.size());
    return results;
  }
}

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialPrice;
import de.greluc.krt.profit.basetool.backend.model.ShipType;
import de.greluc.krt.profit.basetool.backend.model.Terminal;
import de.greluc.krt.profit.basetool.backend.model.dto.ProfitCalculationDto;
import de.greluc.krt.profit.basetool.backend.repository.MaterialPriceRepository;
import de.greluc.krt.profit.basetool.backend.repository.ShipTypeRepository;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProfitCalculationServiceTest {

  @Mock private MaterialPriceRepository materialPriceRepository;

  @Mock private ShipTypeRepository shipTypeRepository;

  @InjectMocks private ProfitCalculationService profitCalculationService;

  @Test
  void shouldCalculateProfitAndSortByMaterialName() {
    // Given
    UUID shipId = UUID.randomUUID();
    ShipType ship = new ShipType();
    ship.setId(shipId);
    ship.setName("C2 Hercules");
    ship.setScu(696);

    Material m1 = new Material();
    m1.setId(UUID.randomUUID());
    m1.setName("Laranite");

    Material m2 = new Material();
    m2.setId(UUID.randomUUID());
    m2.setName("Agricium");

    Material m3 = new Material();
    m3.setId(UUID.randomUUID());
    m3.setName("Zeyneh");

    Terminal t = new Terminal();
    t.setIsAutoLoad(true);
    t.setStarSystemName("Stanton");

    MaterialPrice p1 = new MaterialPrice();
    p1.setMaterial(m1);
    p1.setTerminal(t);
    p1.setPriceBuy(BigDecimal.valueOf(20));
    p1.setPriceSell(BigDecimal.valueOf(30));

    MaterialPrice p2 = new MaterialPrice();
    p2.setMaterial(m2);
    p2.setTerminal(t);
    p2.setPriceBuy(BigDecimal.valueOf(10));
    p2.setPriceSell(BigDecimal.valueOf(15));

    MaterialPrice p3 = new MaterialPrice();
    p3.setMaterial(m3);
    p3.setTerminal(t);
    p3.setPriceBuy(BigDecimal.valueOf(5));
    p3.setPriceSell(BigDecimal.valueOf(20));

    when(shipTypeRepository.findById(shipId)).thenReturn(Optional.of(ship));
    when(materialPriceRepository.findAllAutoLoadPrices()).thenReturn(List.of(p1, p2, p3));

    // When
    List<ProfitCalculationDto> result = profitCalculationService.calculateProfit(shipId, null);

    // Then
    assertNotNull(result);
    assertEquals(3, result.size());

    // Sollte alphabetisch sortiert sein: Agricium, Laranite, Zeyneh
    assertEquals("Agricium", result.get(0).materialName());
    assertEquals("Laranite", result.get(1).materialName());
    assertEquals("Zeyneh", result.get(2).materialName());
  }

  // ----------------------------------------------------------------
  // ShipType lookup
  // ----------------------------------------------------------------

  @Test
  void shouldThrowIllegalArgument_whenShipTypeDoesNotExist() {
    UUID shipId = UUID.randomUUID();
    when(shipTypeRepository.findById(shipId)).thenReturn(Optional.empty());

    assertThrows(
        IllegalArgumentException.class,
        () -> profitCalculationService.calculateProfit(shipId, null));
    verify(materialPriceRepository, never()).findAllAutoLoadPrices();
  }

  @Test
  void shouldUseZeroScu_whenShipScuIsNull() {
    // Defensive: ship without an SCU field set (e.g. small fighter) -> full-load
    // economics collapse to zero, but the calculation must NOT NPE.
    UUID shipId = UUID.randomUUID();
    ShipType ship = newShip(shipId, "Aurora", null);

    Material material = newMaterial("Quantanium");
    Terminal terminal = newTerminal(true);
    MaterialPrice price = newPrice(material, terminal, 10, 20);

    when(shipTypeRepository.findById(shipId)).thenReturn(Optional.of(ship));
    when(materialPriceRepository.findAllAutoLoadPrices()).thenReturn(List.of(price));

    List<ProfitCalculationDto> result = profitCalculationService.calculateProfit(shipId, null);

    assertEquals(1, result.size());
    // fullLoadCost = 10 * 0 = 0; maxProfitFullLoad = 10 * 0 = 0
    assertEquals(0, BigDecimal.ZERO.compareTo(result.get(0).fullLoadCost()));
    assertEquals(0, BigDecimal.ZERO.compareTo(result.get(0).maxProfitFullLoad()));
    // Per-SCU economics must still be correct.
    assertEquals(0, BigDecimal.valueOf(10).compareTo(result.get(0).profitPerScu()));
  }

  // ----------------------------------------------------------------
  // Star-system scoping
  // ----------------------------------------------------------------

  @Test
  void emptyStarSystemList_shouldHitGlobalQuery() {
    UUID shipId = UUID.randomUUID();
    when(shipTypeRepository.findById(shipId))
        .thenReturn(Optional.of(newShip(shipId, "Cutlass", 46)));
    when(materialPriceRepository.findAllAutoLoadPrices()).thenReturn(List.of());

    profitCalculationService.calculateProfit(shipId, Collections.emptyList());

    verify(materialPriceRepository).findAllAutoLoadPrices();
    verify(materialPriceRepository, never()).findAllAutoLoadPricesInSystems(any());
  }

  @Test
  void nonEmptyStarSystemList_shouldHitFilteredQuery() {
    UUID shipId = UUID.randomUUID();
    when(shipTypeRepository.findById(shipId))
        .thenReturn(Optional.of(newShip(shipId, "Cutlass", 46)));
    when(materialPriceRepository.findAllAutoLoadPricesInSystems(List.of("Stanton")))
        .thenReturn(List.of());

    profitCalculationService.calculateProfit(shipId, List.of("Stanton"));

    verify(materialPriceRepository).findAllAutoLoadPricesInSystems(List.of("Stanton"));
    verify(materialPriceRepository, never()).findAllAutoLoadPrices();
  }

  // ----------------------------------------------------------------
  // Hull C filter — only terminals with hasLoadingDock=true survive
  // ----------------------------------------------------------------

  @Test
  void hullC_keepsOnlyTerminalsWithLoadingDock() {
    UUID shipId = UUID.randomUUID();
    ShipType hullC = newShip(shipId, "Hull C", 4608);

    Material material = newMaterial("Quantanium");
    MaterialPrice atDock = newPrice(material, newTerminal(true), 10, 20);
    MaterialPrice atOutpost =
        newPrice(material, newTerminal(false), 5, 25); // better margin but no dock
    MaterialPrice atUnknown = newPrice(material, newTerminal(null), 3, 30); // null hasLoadingDock

    when(shipTypeRepository.findById(shipId)).thenReturn(Optional.of(hullC));
    when(materialPriceRepository.findAllAutoLoadPrices())
        .thenReturn(List.of(atDock, atOutpost, atUnknown));

    List<ProfitCalculationDto> result = profitCalculationService.calculateProfit(shipId, null);

    // Only the dock-equipped terminal contributes:
    // minBuy=10 (not 3 or 5), maxSell=20 (not 25 or 30).
    assertEquals(1, result.size());
    assertEquals(0, BigDecimal.valueOf(10).compareTo(result.get(0).minBuyPrice()));
    assertEquals(0, BigDecimal.valueOf(20).compareTo(result.get(0).maxSellPrice()));
  }

  @Test
  void caseInsensitiveHullCMatch() {
    // The Hull C name check uses toLowerCase().contains("hull c") — make sure
    // mixed-case names still trigger the filter.
    UUID shipId = UUID.randomUUID();
    ShipType hullC = newShip(shipId, "RSI HULL c MERCHANT", 4608);

    Material material = newMaterial("Quantanium");
    MaterialPrice atDock = newPrice(material, newTerminal(true), 10, 20);
    MaterialPrice atOutpost = newPrice(material, newTerminal(false), 5, 25);

    when(shipTypeRepository.findById(shipId)).thenReturn(Optional.of(hullC));
    when(materialPriceRepository.findAllAutoLoadPrices()).thenReturn(List.of(atDock, atOutpost));

    List<ProfitCalculationDto> result = profitCalculationService.calculateProfit(shipId, null);

    assertEquals(
        1,
        result.size(),
        "case-insensitive substring match must still recognise the Hull C constraint");
    assertEquals(0, BigDecimal.valueOf(10).compareTo(result.get(0).minBuyPrice()));
  }

  @Test
  void shipWithNullName_doesNotTriggerHullCFilter() {
    // Defensive: if name is null, the Hull C check must NOT NPE on null.toLowerCase().
    UUID shipId = UUID.randomUUID();
    ShipType ship = newShip(shipId, null, 50);

    Material material = newMaterial("Quantanium");
    MaterialPrice anywhere = newPrice(material, newTerminal(false), 10, 20);

    when(shipTypeRepository.findById(shipId)).thenReturn(Optional.of(ship));
    when(materialPriceRepository.findAllAutoLoadPrices()).thenReturn(List.of(anywhere));

    List<ProfitCalculationDto> result = profitCalculationService.calculateProfit(shipId, null);

    // Outpost (no loading dock) still counts because the ship isn't a Hull C.
    assertEquals(1, result.size());
  }

  // ----------------------------------------------------------------
  // Price-zero / price-null filtering
  // ----------------------------------------------------------------

  @Test
  void priceWithNullBuy_isSkipped() {
    // No buy price -> minBuy resolves to null -> material is filtered out entirely.
    UUID shipId = UUID.randomUUID();
    ShipType ship = newShip(shipId, "Cutlass", 46);

    Material material = newMaterial("Quantanium");
    MaterialPrice nullBuy = new MaterialPrice();
    nullBuy.setMaterial(material);
    nullBuy.setTerminal(newTerminal(true));
    nullBuy.setPriceBuy(null);
    nullBuy.setPriceSell(BigDecimal.valueOf(20));

    when(shipTypeRepository.findById(shipId)).thenReturn(Optional.of(ship));
    when(materialPriceRepository.findAllAutoLoadPrices()).thenReturn(List.of(nullBuy));

    List<ProfitCalculationDto> result = profitCalculationService.calculateProfit(shipId, null);

    assertTrue(
        result.isEmpty(), "material with no positive buy price must NOT appear in the result");
  }

  @Test
  void priceWithZeroBuy_isSkipped() {
    UUID shipId = UUID.randomUUID();
    ShipType ship = newShip(shipId, "Cutlass", 46);

    Material material = newMaterial("Quantanium");
    MaterialPrice zeroBuy = new MaterialPrice();
    zeroBuy.setMaterial(material);
    zeroBuy.setTerminal(newTerminal(true));
    zeroBuy.setPriceBuy(BigDecimal.ZERO);
    zeroBuy.setPriceSell(BigDecimal.valueOf(20));

    when(shipTypeRepository.findById(shipId)).thenReturn(Optional.of(ship));
    when(materialPriceRepository.findAllAutoLoadPrices()).thenReturn(List.of(zeroBuy));

    assertTrue(
        profitCalculationService.calculateProfit(shipId, null).isEmpty(),
        "zero buy price means the material is not actually available — must skip");
  }

  @Test
  void priceWithNullSell_isSkipped() {
    UUID shipId = UUID.randomUUID();
    ShipType ship = newShip(shipId, "Cutlass", 46);

    Material material = newMaterial("Quantanium");
    MaterialPrice nullSell = new MaterialPrice();
    nullSell.setMaterial(material);
    nullSell.setTerminal(newTerminal(true));
    nullSell.setPriceBuy(BigDecimal.valueOf(10));
    nullSell.setPriceSell(null);

    when(shipTypeRepository.findById(shipId)).thenReturn(Optional.of(ship));
    when(materialPriceRepository.findAllAutoLoadPrices()).thenReturn(List.of(nullSell));

    assertTrue(profitCalculationService.calculateProfit(shipId, null).isEmpty());
  }

  @Test
  void priceWithZeroSell_isSkipped() {
    UUID shipId = UUID.randomUUID();
    ShipType ship = newShip(shipId, "Cutlass", 46);

    Material material = newMaterial("Quantanium");
    MaterialPrice zeroSell = new MaterialPrice();
    zeroSell.setMaterial(material);
    zeroSell.setTerminal(newTerminal(true));
    zeroSell.setPriceBuy(BigDecimal.valueOf(10));
    zeroSell.setPriceSell(BigDecimal.ZERO);

    when(shipTypeRepository.findById(shipId)).thenReturn(Optional.of(ship));
    when(materialPriceRepository.findAllAutoLoadPrices()).thenReturn(List.of(zeroSell));

    assertTrue(profitCalculationService.calculateProfit(shipId, null).isEmpty());
  }

  @Test
  void mixedTerminalsForOneMaterial_useMinBuyAndMaxSell() {
    // Three terminals for the same material with different prices —
    // the calculation must take min(buy) and max(sell) across terminals.
    UUID shipId = UUID.randomUUID();
    ShipType ship = newShip(shipId, "Cutlass", 100);

    Material material = newMaterial("Quantanium");

    MaterialPrice cheapBuy = newPrice(material, newTerminal(true), 5, 12);
    MaterialPrice midBuy = newPrice(material, newTerminal(true), 8, 15);
    MaterialPrice bestSell = newPrice(material, newTerminal(true), 9, 30);

    when(shipTypeRepository.findById(shipId)).thenReturn(Optional.of(ship));
    when(materialPriceRepository.findAllAutoLoadPrices())
        .thenReturn(List.of(cheapBuy, midBuy, bestSell));

    List<ProfitCalculationDto> result = profitCalculationService.calculateProfit(shipId, null);

    assertEquals(1, result.size());
    ProfitCalculationDto row = result.get(0);
    assertEquals(
        0,
        BigDecimal.valueOf(5).compareTo(row.minBuyPrice()),
        "minBuy must be 5 (the cheapest of 5/8/9)");
    assertEquals(
        0,
        BigDecimal.valueOf(30).compareTo(row.maxSellPrice()),
        "maxSell must be 30 (the most expensive of 12/15/30)");
    // profitPerScu = 30 - 5 = 25; fullLoadCost = 5 * 100 = 500; maxProfit = 25 * 100 = 2500.
    assertEquals(0, BigDecimal.valueOf(25).compareTo(row.profitPerScu()));
    assertEquals(0, BigDecimal.valueOf(500).compareTo(row.fullLoadCost()));
    assertEquals(0, BigDecimal.valueOf(2500).compareTo(row.maxProfitFullLoad()));
  }

  @Test
  void marginPercent_isComputedAsBuyPercentage() {
    // minBuy=10, maxSell=30 -> margin = (30-10)/10 * 100 = 200.0000%
    UUID shipId = UUID.randomUUID();
    ShipType ship = newShip(shipId, "Cutlass", 1);

    Material material = newMaterial("Quantanium");
    MaterialPrice price = newPrice(material, newTerminal(true), 10, 30);

    when(shipTypeRepository.findById(shipId)).thenReturn(Optional.of(ship));
    when(materialPriceRepository.findAllAutoLoadPrices()).thenReturn(List.of(price));

    List<ProfitCalculationDto> result = profitCalculationService.calculateProfit(shipId, null);

    BigDecimal margin = result.get(0).marginPercent();
    // 200.0000 with scale 4 (multiply by 100 first, then divide-with-scale-4 keeps the 4 decimals).
    assertEquals(
        0,
        new BigDecimal("200.0000").compareTo(margin),
        "margin must be 200% with the HALF_UP rounding at 4 decimal places");
  }

  @Test
  void smallMargin_isRoundedHalfUpAtFourDecimals() {
    // The implementation does: profitPerScu.multiply(100).divide(minBuy, 4, HALF_UP).
    // For profit=1, minBuy=3: 100.divide(3, 4, HALF_UP) = 33.3333. The multiply-first
    // ordering preserves four meaningful fractional digits in the percent value, so we
    // get 33.3333% (and not the precision-collapsed 33.3300% that divide-then-multiply
    // would yield).
    UUID shipId = UUID.randomUUID();
    ShipType ship = newShip(shipId, "Cutlass", 1);

    Material material = newMaterial("Quantanium");
    MaterialPrice price = newPrice(material, newTerminal(true), 3, 4);

    when(shipTypeRepository.findById(shipId)).thenReturn(Optional.of(ship));
    when(materialPriceRepository.findAllAutoLoadPrices()).thenReturn(List.of(price));

    BigDecimal margin =
        profitCalculationService.calculateProfit(shipId, null).get(0).marginPercent();

    assertEquals(
        0,
        new BigDecimal("33.3333").compareTo(margin),
        "margin must be 33.3333% — multiply-before-divide preserves 4 fractional digits");
  }

  // ----------------------------------------------------------------
  // Loss-making material — both sides positive but maxSell < minBuy
  // ----------------------------------------------------------------

  @Test
  void negativeMargin_materialIsStillIncludedWithNegativeProfit() {
    // A material whose cheapest buy (30) is dearer than its best sell (20). Both
    // sides are individually positive, so it passes the both-sides-positive filter
    // and MUST be kept — the profit page shows loss rows so a trader sees them.
    // profitPerScu = 20 - 30 = -10; margin = -10 * 100 / 30 = -33.3333%;
    // maxProfitFullLoad = -10 * 100 = -1000.
    UUID shipId = UUID.randomUUID();
    ShipType ship = newShip(shipId, "Cutlass", 100);

    Material material = newMaterial("Quantanium");
    MaterialPrice price = newPrice(material, newTerminal(true), 30, 20);

    when(shipTypeRepository.findById(shipId)).thenReturn(Optional.of(ship));
    when(materialPriceRepository.findAllAutoLoadPrices()).thenReturn(List.of(price));

    List<ProfitCalculationDto> result = profitCalculationService.calculateProfit(shipId, null);

    assertEquals(
        1, result.size(), "a loss-making material must NOT be dropped — loss rows are shown");
    ProfitCalculationDto row = result.get(0);
    assertEquals(
        0,
        BigDecimal.valueOf(-10).compareTo(row.profitPerScu()),
        "profitPerScu must be the negative maxSell - minBuy (20 - 30)");
    assertEquals(
        0,
        new BigDecimal("-33.3333").compareTo(row.marginPercent()),
        "margin must be signed and negative — no abs() around the loss");
    assertEquals(
        0,
        BigDecimal.valueOf(-1000).compareTo(row.maxProfitFullLoad()),
        "full-load profit scales the per-SCU loss by the ship's SCU (-10 * 100)");
    assertTrue(
        row.maxProfitFullLoad().compareTo(BigDecimal.ZERO) < 0,
        "a loss ship-load must surface as a negative full-load profit");
  }

  // ----------------------------------------------------------------
  // helpers
  // ----------------------------------------------------------------

  private static ShipType newShip(UUID id, String name, Integer scu) {
    ShipType s = new ShipType();
    s.setId(id);
    s.setName(name);
    s.setScu(scu);
    return s;
  }

  private static Material newMaterial(String name) {
    Material m = new Material();
    m.setId(UUID.randomUUID());
    m.setName(name);
    return m;
  }

  private static Terminal newTerminal(Boolean hasLoadingDock) {
    Terminal t = new Terminal();
    t.setStarSystemName("Stanton");
    t.setHasLoadingDock(hasLoadingDock);
    return t;
  }

  private static MaterialPrice newPrice(Material material, Terminal terminal, int buy, int sell) {
    MaterialPrice p = new MaterialPrice();
    p.setMaterial(material);
    p.setTerminal(terminal);
    p.setPriceBuy(BigDecimal.valueOf(buy));
    p.setPriceSell(BigDecimal.valueOf(sell));
    return p;
  }
}

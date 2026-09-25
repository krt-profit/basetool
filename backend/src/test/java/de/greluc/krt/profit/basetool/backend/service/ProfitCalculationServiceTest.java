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

    List<ProfitCalculationDto> result = profitCalculationService.calculateProfit(shipId, null);

    assertNotNull(result);
    assertEquals(3, result.size());

    assertEquals("Agricium", result.get(0).materialName());
    assertEquals("Laranite", result.get(1).materialName());
    assertEquals("Zeyneh", result.get(2).materialName());
  }

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
    UUID shipId = UUID.randomUUID();
    ShipType ship = newShip(shipId, "Aurora", null);

    Material material = newMaterial("Quantanium");
    Terminal terminal = newTerminal(true);
    MaterialPrice price = newPrice(material, terminal, 10, 20);

    when(shipTypeRepository.findById(shipId)).thenReturn(Optional.of(ship));
    when(materialPriceRepository.findAllAutoLoadPrices()).thenReturn(List.of(price));

    List<ProfitCalculationDto> result = profitCalculationService.calculateProfit(shipId, null);

    assertEquals(1, result.size());
    assertEquals(0, BigDecimal.ZERO.compareTo(result.get(0).fullLoadCost()));
    assertEquals(0, BigDecimal.ZERO.compareTo(result.get(0).maxProfitFullLoad()));
    assertEquals(0, BigDecimal.valueOf(10).compareTo(result.get(0).profitPerScu()));
  }

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

  @Test
  void hullC_keepsOnlyTerminalsWithLoadingDock() {
    UUID shipId = UUID.randomUUID();
    ShipType hullC = newShip(shipId, "Hull C", 4608);

    Material material = newMaterial("Quantanium");
    MaterialPrice atDock = newPrice(material, newTerminal(true), 10, 20);
    MaterialPrice atOutpost = newPrice(material, newTerminal(false), 5, 25);
    MaterialPrice atUnknown = newPrice(material, newTerminal(null), 3, 30);

    when(shipTypeRepository.findById(shipId)).thenReturn(Optional.of(hullC));
    when(materialPriceRepository.findAllAutoLoadPrices())
        .thenReturn(List.of(atDock, atOutpost, atUnknown));

    List<ProfitCalculationDto> result = profitCalculationService.calculateProfit(shipId, null);

    assertEquals(1, result.size());
    assertEquals(0, BigDecimal.valueOf(10).compareTo(result.get(0).minBuyPrice()));
    assertEquals(0, BigDecimal.valueOf(20).compareTo(result.get(0).maxSellPrice()));
  }

  @Test
  void caseInsensitiveHullCMatch() {
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
    UUID shipId = UUID.randomUUID();
    ShipType ship = newShip(shipId, null, 50);

    Material material = newMaterial("Quantanium");
    MaterialPrice anywhere = newPrice(material, newTerminal(false), 10, 20);

    when(shipTypeRepository.findById(shipId)).thenReturn(Optional.of(ship));
    when(materialPriceRepository.findAllAutoLoadPrices()).thenReturn(List.of(anywhere));

    List<ProfitCalculationDto> result = profitCalculationService.calculateProfit(shipId, null);

    assertEquals(1, result.size());
  }

  @Test
  void priceWithNullBuy_isSkipped() {
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
    assertEquals(0, BigDecimal.valueOf(25).compareTo(row.profitPerScu()));
    assertEquals(0, BigDecimal.valueOf(500).compareTo(row.fullLoadCost()));
    assertEquals(0, BigDecimal.valueOf(2500).compareTo(row.maxProfitFullLoad()));
  }

  @Test
  void marginPercent_isComputedAsBuyPercentage() {
    UUID shipId = UUID.randomUUID();
    ShipType ship = newShip(shipId, "Cutlass", 1);

    Material material = newMaterial("Quantanium");
    MaterialPrice price = newPrice(material, newTerminal(true), 10, 30);

    when(shipTypeRepository.findById(shipId)).thenReturn(Optional.of(ship));
    when(materialPriceRepository.findAllAutoLoadPrices()).thenReturn(List.of(price));

    List<ProfitCalculationDto> result = profitCalculationService.calculateProfit(shipId, null);

    BigDecimal margin = result.get(0).marginPercent();
    assertEquals(
        0,
        new BigDecimal("200.0000").compareTo(margin),
        "margin must be 200% with the HALF_UP rounding at 4 decimal places");
  }

  @Test
  void smallMargin_isRoundedHalfUpAtFourDecimals() {
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

  @Test
  void negativeMargin_materialIsStillIncludedWithNegativeProfit() {
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

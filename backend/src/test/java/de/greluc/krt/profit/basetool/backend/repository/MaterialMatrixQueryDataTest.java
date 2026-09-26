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

package de.greluc.krt.profit.basetool.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialPrice;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.Terminal;
import de.greluc.krt.profit.basetool.backend.model.dto.MaterialMatrixItemDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies {@link MaterialPriceRepository#findMatrixItems} against PostgreSQL: the four optional
 * filters, their intersection and the base predicates (REQ-UI-014). Fixtures use unique names; each
 * test rolls back.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MaterialMatrixQueryDataTest {

  private static final Pageable PAGE = PageRequest.of(0, 1000);

  @Autowired private MaterialPriceRepository materialPriceRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private TerminalRepository terminalRepository;

  private String uid;
  private String aluminum;
  private String titanium;
  private String stanton;
  private String pyro;
  private String area18;
  private String lorville;
  private String pyroRuin;
  private String hiddenDock;
  private String staleDock;

  @BeforeEach
  void seed() {
    uid = UUID.randomUUID().toString();
    aluminum = "Aluminum-" + uid;
    titanium = "Titanium-" + uid;
    stanton = "Stanton-" + uid;
    pyro = "Pyro-" + uid;
    area18 = "Area18-" + uid;
    lorville = "Lorville-" + uid;
    pyroRuin = "Ruin-" + uid;
    hiddenDock = "HiddenDock-" + uid;
    staleDock = "StaleDock-" + uid;

    Material alu = persistMaterial(aluminum);
    Material tit = persistMaterial(titanium);

    Terminal tArea18 = persistTerminal(area18, stanton, true, true, false);
    Terminal tLorville = persistTerminal(lorville, stanton, false, false, false);
    Terminal tRuin = persistTerminal(pyroRuin, pyro, true, false, false);
    Terminal tHidden = persistTerminal(hiddenDock, stanton, true, true, true);
    Terminal tStale = persistTerminal(staleDock, stanton, true, true, false);

    persistSellPrice(alu, tArea18, 100);
    persistBuyPrice(alu, tLorville, 50);
    persistSellPrice(alu, tRuin, 80);
    persistSellPrice(tit, tArea18, 200);
    persistSellPrice(alu, tHidden, 999);
    persistNeutralisedPrice(alu, tStale);
  }

  @Test
  void materialNamesFilter_keepsOnlyNamedMaterials_andExcludesHiddenTerminal() {
    Page<MaterialMatrixItemDto> page =
        materialPriceRepository.findMatrixItems(List.of(aluminum), null, null, null, PAGE);

    assertThat(page.getContent())
        .extracting(MaterialMatrixItemDto::terminalName)
        .containsExactlyInAnyOrder(area18, lorville, pyroRuin);
    assertThat(page.getContent())
        .extracting(MaterialMatrixItemDto::materialName)
        .containsOnly(aluminum);
    assertThat(page.getContent())
        .extracting(MaterialMatrixItemDto::terminalName)
        .doesNotContain(hiddenDock);
  }

  @Test
  void starSystemsFilter_restrictsToNamedSystems() {
    Page<MaterialMatrixItemDto> page =
        materialPriceRepository.findMatrixItems(null, List.of(pyro), null, null, PAGE);

    assertThat(page.getContent())
        .extracting(MaterialMatrixItemDto::terminalName)
        .containsExactly(pyroRuin);
    assertThat(page.getContent())
        .extracting(MaterialMatrixItemDto::materialName)
        .containsExactly(aluminum);
  }

  @Test
  void hasLoadingDockFilter_keepsOnlyDockTerminals() {
    Page<MaterialMatrixItemDto> page =
        materialPriceRepository.findMatrixItems(
            null, List.of(stanton, pyro), Boolean.TRUE, null, PAGE);

    assertThat(page.getContent())
        .extracting(MaterialMatrixItemDto::terminalName)
        .containsExactlyInAnyOrder(area18, area18, pyroRuin);
    assertThat(page.getContent())
        .extracting(MaterialMatrixItemDto::terminalName)
        .doesNotContain(lorville, hiddenDock);
  }

  @Test
  void isAutoLoadFilter_keepsOnlyAutoLoadTerminals() {
    Page<MaterialMatrixItemDto> page =
        materialPriceRepository.findMatrixItems(
            null, List.of(stanton, pyro), null, Boolean.TRUE, PAGE);

    assertThat(page.getContent())
        .extracting(MaterialMatrixItemDto::terminalName)
        .containsExactlyInAnyOrder(area18, area18);
    assertThat(page.getContent())
        .extracting(MaterialMatrixItemDto::materialName)
        .containsExactlyInAnyOrder(aluminum, titanium);
  }

  @Test
  void combinedFilters_intersectAcrossEveryDimension() {
    Page<MaterialMatrixItemDto> page =
        materialPriceRepository.findMatrixItems(
            List.of(aluminum), List.of(stanton), Boolean.TRUE, null, PAGE);

    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).terminalName()).isEqualTo(area18);
    assertThat(page.getContent().get(0).materialName()).isEqualTo(aluminum);
  }

  @Test
  void noFilters_returnsEveryActiveSeededRow_butNeverTheHiddenTerminal() {
    Page<MaterialMatrixItemDto> page =
        materialPriceRepository.findMatrixItems(null, List.of(stanton, pyro), null, null, PAGE);

    assertThat(page.getContent())
        .extracting(MaterialMatrixItemDto::terminalName)
        .containsExactlyInAnyOrder(area18, area18, lorville, pyroRuin);
    assertThat(page.getContent())
        .extracting(MaterialMatrixItemDto::terminalName)
        .doesNotContain(hiddenDock, staleDock);
  }

  @Test
  void neutralisedPrice_isExcludedByActiveSidePredicate_notByHiddenOrFilter() {
    Page<MaterialMatrixItemDto> unfiltered =
        materialPriceRepository.findMatrixItems(null, List.of(stanton, pyro), null, null, PAGE);
    assertThat(unfiltered.getContent())
        .extracting(MaterialMatrixItemDto::terminalName)
        .doesNotContain(staleDock);

    Page<MaterialMatrixItemDto> matchingFilter =
        materialPriceRepository.findMatrixItems(
            List.of(aluminum), List.of(stanton), Boolean.TRUE, Boolean.TRUE, PAGE);
    assertThat(matchingFilter.getContent())
        .extracting(MaterialMatrixItemDto::terminalName)
        .doesNotContain(staleDock);
  }

  private Material persistMaterial(String name) {
    Material material = new Material();
    material.setName(name);
    material.setType(MaterialType.RAW);
    material.setIsIllegal(0);
    material.setIsVolatileQt(0);
    material.setIsVolatileTime(0);
    return materialRepository.save(material);
  }

  private Terminal persistTerminal(
      String name, String starSystemName, boolean dock, boolean autoLoad, boolean hidden) {
    Terminal terminal = new Terminal();
    terminal.setName(name);
    terminal.setStarSystemName(starSystemName);
    terminal.setHasLoadingDock(dock);
    terminal.setIsAutoLoad(autoLoad);
    terminal.setHidden(hidden);
    return terminalRepository.save(terminal);
  }

  private void persistSellPrice(Material material, Terminal terminal, long sell) {
    persistPrice(material, terminal, null, BigDecimal.valueOf(sell));
  }

  private void persistBuyPrice(Material material, Terminal terminal, long buy) {
    persistPrice(material, terminal, BigDecimal.valueOf(buy), null);
  }

  /**
   * Persists a neutralised price row — both sides inactive and price-less, the shape the UEX
   * stale-row sweep leaves behind — so the matrix query's active-side predicate must exclude it.
   *
   * @param material the material
   * @param terminal the terminal
   */
  private void persistNeutralisedPrice(Material material, Terminal terminal) {
    persistPrice(material, terminal, null, null);
  }

  private void persistPrice(Material material, Terminal terminal, BigDecimal buy, BigDecimal sell) {
    MaterialPrice price = new MaterialPrice();
    price.setMaterial(material);
    price.setTerminal(terminal);
    price.setPriceBuy(buy);
    price.setPriceSell(sell);
    price.setStatusBuy(buy != null);
    price.setStatusSell(sell != null);
    materialPriceRepository.save(price);
  }
}

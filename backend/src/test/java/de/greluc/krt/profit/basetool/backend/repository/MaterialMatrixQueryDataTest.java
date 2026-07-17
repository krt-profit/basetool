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
 * Data-level coverage for {@link MaterialPriceRepository#findMatrixItems} against the real Postgres
 * test schema (Testcontainers + Flyway via the {@code test} profile). It pins the server-side
 * matrix filtering added for ADR-0105 / REQ-UI-014: the four optional dimensions (material names,
 * star systems, has-loading-dock, is-auto-load) applied through the {@code :param IS NULL OR …}
 * idiom, their intersection, and that the base predicates (hidden terminals excluded, only rows
 * with an active buy/sell side) still hold under every filter combination.
 *
 * <p>All fixtures use per-run unique names (a random suffix) and are always queried by those exact
 * names, so the assertions are isolated from any material / terminal rows the shared Testcontainers
 * database already carries — the counts below are exact for the seeded set, never the whole
 * catalogue. {@link Transactional} rolls each method back so nothing commits to the shared
 * database.
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

    // Stanton: Area18 has both a dock and auto-load; Lorville has neither.
    Terminal tArea18 = persistTerminal(area18, stanton, true, true, false);
    Terminal tLorville = persistTerminal(lorville, stanton, false, false, false);
    // Pyro: a dock terminal without auto-load.
    Terminal tRuin = persistTerminal(pyroRuin, pyro, true, false, false);
    // A hidden Stanton dock terminal — must never surface regardless of the filters.
    Terminal tHidden = persistTerminal(hiddenDock, stanton, true, true, true);
    // A VISIBLE Stanton dock+auto-load terminal whose only Aluminum price has been neutralised by
    // the UEX stale-row sweep (both sides inactive). It matches every filter dimension the active
    // terminals do, so the ONLY thing that can exclude it is the active buy/sell-side predicate —
    // not the hidden flag, not any filter. It proves that predicate is really enforced.
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

    // Aluminum trades at Area18, Lorville and Ruin — the hidden terminal's Aluminum row is dropped.
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

    // Only the Pyro terminal (Ruin) is in scope; it trades Aluminum.
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

    // Dock terminals in my systems: Area18 (Aluminum + Titanium) and Ruin (Aluminum). Lorville has
    // no dock; the hidden dock terminal is excluded by the base predicate.
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

    // Only Area18 auto-loads (Ruin does not); it trades Aluminum and Titanium.
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

    // Aluminum AND Stanton AND has-dock ⇒ Area18 only (Lorville has no dock, Ruin is Pyro, the
    // hidden dock terminal is excluded).
    assertThat(page.getContent()).hasSize(1);
    assertThat(page.getContent().get(0).terminalName()).isEqualTo(area18);
    assertThat(page.getContent().get(0).materialName()).isEqualTo(aluminum);
  }

  @Test
  void noFilters_returnsEveryActiveSeededRow_butNeverTheHiddenTerminal() {
    // Scope to my two star systems (still "no material / dock / auto-load filter") so the count is
    // exact against the seeded set rather than the shared catalogue.
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
    // The stale terminal is visible and matches every filter dimension the active terminals do
    // (Stanton, dock, auto-load, trades Aluminum). It appears in neither the unfiltered
    // (system-scoped) result nor a filter selection that its own attributes satisfy — so the only
    // thing excluding it is the active buy/sell-side predicate. If that WHERE clause were dropped,
    // the stale terminal would surface and these assertions would fail.
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

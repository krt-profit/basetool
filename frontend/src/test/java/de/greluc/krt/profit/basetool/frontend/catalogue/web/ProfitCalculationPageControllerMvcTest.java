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

package de.greluc.krt.profit.basetool.frontend.catalogue.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.TerminalDto;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
class ProfitCalculationPageControllerMvcTest {

  private MockMvc mockMvc;

  @Autowired private WebApplicationContext context;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void showProfitCalculationPage_ShouldSetDefaultShipId_WhenC2IsPresent() throws Exception {
    UUID c2Id = UUID.randomUUID();
    ShipTypeDto c2 = new ShipTypeDto(c2Id, "C2 Hercules Starlifter", null, "C2", 696, false);
    PageResponse<ShipTypeDto> shipTypes = new PageResponse<>(List.of(c2), 0, 10, 1, 1, List.of());

    PageResponse<TerminalDto> terminals =
        new PageResponse<>(List.of(stantonTerminal()), 0, 10, 1, 1, List.of());

    when(backendApiClient.getCached(eq(CachedCatalog.SHIP_TYPES_SORTED), anyTypeRef()))
        .thenReturn(shipTypes);
    when(backendApiClient.getCached(eq(CachedCatalog.TERMINALS), anyTypeRef()))
        .thenReturn(terminals);

    mockMvc
        .perform(get("/materials/profit-calculation"))
        .andExpect(status().isOk())
        .andExpect(view().name("materials-profit-calculation"))
        .andExpect(model().attribute("defaultShipId", c2Id))
        .andExpect(model().attributeExists("shipTypes"))
        .andExpect(model().attributeExists("starSystems"));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void showProfitCalculationPage_ShouldNotSetDefaultShipId_WhenC2IsMissing() throws Exception {
    ShipTypeDto titan =
        new ShipTypeDto(UUID.randomUUID(), "Avenger Titan", null, "Titan", 8, false);
    PageResponse<ShipTypeDto> shipTypes =
        new PageResponse<>(List.of(titan), 0, 10, 1, 1, List.of());

    PageResponse<TerminalDto> terminals = new PageResponse<>(List.of(), 0, 10, 0, 1, List.of());

    when(backendApiClient.getCached(eq(CachedCatalog.SHIP_TYPES_SORTED), anyTypeRef()))
        .thenReturn(shipTypes);
    when(backendApiClient.getCached(eq(CachedCatalog.TERMINALS), anyTypeRef()))
        .thenReturn(terminals);

    mockMvc
        .perform(get("/materials/profit-calculation"))
        .andExpect(status().isOk())
        .andExpect(view().name("materials-profit-calculation"))
        .andExpect(model().attributeDoesNotExist("defaultShipId"));
  }

  /**
   * The page renders on the calculator pattern: page head, the inputs in one card with the ship
   * select (Hull C ships marked), the systems dropdown and the hidden Hull C chip, and the result
   * table with its state line instead of a colspan message row.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void showProfitCalculationPage_rendersTheCalculatorPattern() throws Exception {
    ShipTypeDto hullC =
        new ShipTypeDto(UUID.randomUUID(), "MISC Hull C", null, "Hull C", 4608, false);
    ShipTypeDto titan =
        new ShipTypeDto(UUID.randomUUID(), "Avenger Titan", null, "Titan", 8, false);
    when(backendApiClient.getCached(eq(CachedCatalog.SHIP_TYPES_SORTED), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(hullC, titan), 0, 10, 2, 1, List.of()));
    when(backendApiClient.getCached(eq(CachedCatalog.TERMINALS), anyTypeRef()))
        .thenReturn(new PageResponse<>(List.of(stantonTerminal()), 0, 10, 1, 1, List.of()));

    String html =
        mockMvc
            .perform(get("/materials/profit-calculation").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Handel<")
        .containsPattern("<h1>Profitberechnung</h1>")
        .doesNotContain("class=\"greeting")
        .doesNotContain("hud-box")
        .doesNotContain("krtm-")
        .doesNotContain("colspan")
        .doesNotContain("btn--cta")
        .doesNotContain("window.krtProfitI18n")
        .contains("class=\"card profit-inputs\"")
        .containsPattern("data-hull-c=\"true\"[^>]*>MISC Hull C · 4608 SCU<")
        .containsPattern("data-hull-c=\"false\"[^>]*>Avenger Titan · 8 SCU<")
        .containsPattern("id=\"systemHeader\"[^>]*aria-controls=\"systemOptions\"")
        .containsPattern("id=\"systemOptions\"[^>]*data-filter-transient")
        .containsPattern("id=\"profitHullC\"[^>]*hidden")
        .contains("id=\"resultsTable\" class=\"data-table data-table--stack profit-table\"")
        .contains("<tbody id=\"profitBody\"></tbody>")
        .contains("id=\"profitState\"");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void showProfitCalculationPage_ShouldFilterShipsWithZeroScu() throws Exception {
    UUID titanId = UUID.randomUUID();
    ShipTypeDto gladius =
        new ShipTypeDto(UUID.randomUUID(), "Aegis Gladius", null, "Gladius", 0, false);
    ShipTypeDto titan = new ShipTypeDto(titanId, "Avenger Titan", null, "Titan", 8, false);
    ShipTypeDto noScu = new ShipTypeDto(UUID.randomUUID(), "No SCU", null, "No SCU", null, false);

    PageResponse<ShipTypeDto> shipTypes =
        new PageResponse<>(List.of(gladius, titan, noScu), 0, 10, 3, 1, List.of());

    PageResponse<TerminalDto> terminals = new PageResponse<>(List.of(), 0, 10, 0, 1, List.of());

    when(backendApiClient.getCached(eq(CachedCatalog.SHIP_TYPES_SORTED), anyTypeRef()))
        .thenReturn(shipTypes);
    when(backendApiClient.getCached(eq(CachedCatalog.TERMINALS), anyTypeRef()))
        .thenReturn(terminals);

    mockMvc
        .perform(get("/materials/profit-calculation"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("shipTypes", List.of(titan)));
  }

  /**
   * Builds a Stanton terminal row of the cached terminal catalogue.
   *
   * @return the terminal
   */
  private static TerminalDto stantonTerminal() {
    return new TerminalDto(
        UUID.fromString("0d6b3e1a-7c42-4f58-9a1e-3b5c7d9e2f40"),
        "Area18 TDD",
        null,
        "Stanton",
        "ArcCorp",
        "Area18",
        null,
        true,
        false,
        false,
        false,
        true,
        false,
        null,
        false);
  }
}

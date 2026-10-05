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

package de.greluc.krt.profit.basetool.frontend.controller;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.ManufacturerDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.ShipTypeDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.PageStylesheets;
import java.util.Collections;
import java.util.List;
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

/**
 * Render test for {@code /ship-data}: the {@code ship-data.js} module tag after the inline
 * bootstrap is present and the response ends with {@code </html>}, guarding against inline-script
 * truncation, and the page follows the list pattern (REQ-UI-027).
 */
@SpringBootTest
class ShipDataPageControllerMvcTest {

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

  /**
   * Stubs one manufacturer and one ship type, or none of either.
   *
   * @param empty whether both catalogues come back empty
   */
  private void stubCatalogue(boolean empty) {
    ManufacturerDto manufacturer =
        new ManufacturerDto(
            UUID.randomUUID(),
            "Aegis Dynamics",
            "AEGS",
            "Aegis",
            "https://example/aegis",
            "Mil-style",
            false);
    ShipTypeDto shipType =
        new ShipTypeDto(UUID.randomUUID(), "Avenger Titan", manufacturer, "Titan", 8, true);
    List<ManufacturerDto> manufacturers = empty ? List.of() : List.of(manufacturer);
    List<ShipTypeDto> shipTypes = empty ? List.of() : List.of(shipType);
    PageResponse<ManufacturerDto> manufacturersPage =
        new PageResponse<>(
            manufacturers, 0, 1000, manufacturers.size(), 1, Collections.emptyList());
    PageResponse<ShipTypeDto> shipTypesPage =
        new PageResponse<>(shipTypes, 0, 1000, shipTypes.size(), 1, Collections.emptyList());

    when(backendApiClient.get(
            eq("/api/v1/manufacturers?size=1000&sort=name,asc&includeHidden=true&page={page}"),
            anyTypeRef(),
            eq(0)))
        .thenReturn(manufacturersPage);
    when(backendApiClient.get(
            eq("/api/v1/ship-types?size=1000&sort=name,asc&includeHidden=true&page={page}"),
            anyTypeRef(),
            eq(0)))
        .thenReturn(shipTypesPage);
  }

  /**
   * Asserts the extracted {@code ship-data.js} module tag (emitted AFTER both datalists and the
   * interpolated bootstrap) appears in the rendered HTML — proof that the Thymeleaf inline
   * truncation does not strike again.
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void listData_ShouldRenderModuleTag_AfterBothDatalists() throws Exception {
    stubCatalogue(false);

    mockMvc
        .perform(get("/ship-data"))
        .andExpect(status().isOk())
        .andExpect(view().name("ship-data"))
        .andExpect(content().string(containsString("id=\"shipTypeNames-data\"")))
        .andExpect(content().string(containsString("id=\"mfgNames-data\"")))
        .andExpect(content().string(containsString("value=\"Avenger Titan\"")))
        .andExpect(content().string(containsString("value=\"Aegis Dynamics\"")))
        .andExpect(content().string(containsString("src=\"/js/ship-data.js\"")))
        .andExpect(PageStylesheets.content(containsString(".sd-row--hidden")))
        .andExpect(content().string(containsString("</html>")));
  }

  /**
   * An admin sees the list pattern: the page head with the master-data eyebrow and the reset in its
   * overflow menu, both catalogues as stacked tables in flush cards, row toggles as quiet buttons,
   * a hidden entry marked by its row class, and no HUD box or greeting banner.
   *
   * @throws Exception if the request fails
   */
  @Test
  @WithMockUser(roles = "ADMIN")
  void anAdminSeesTheListPatternWithTheResetInTheOverflowMenu() throws Exception {
    stubCatalogue(false);

    String html =
        mockMvc
            .perform(get("/ship-data"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("class=\"page-head\"")
        .containsPattern("class=\"page-eyebrow\"[^>]*>Stammdaten<")
        .contains("data-testid=\"overflow-menu-toggle\"")
        .contains("data-testid=\"ship-data-reset-fitted\"")
        .contains("id=\"shipTypesTable\" class=\"data-table data-table--stack\"")
        .contains("id=\"manufacturersTable\" class=\"data-table data-table--stack\"")
        .contains("class=\"btn btn-ghost btn-xs\"")
        .contains("sd-row--hidden")
        .doesNotContain("krtm-")
        .doesNotContain("hud-box")
        .doesNotContain("class=\"greeting")
        .doesNotContain("btn--cta");
  }

  /**
   * A member sees the same lists without any admin control: no overflow menu, no visibility toggle,
   * no reset dialog.
   *
   * @throws Exception if the request fails
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void aMemberSeesNoAdminControls() throws Exception {
    stubCatalogue(false);

    String html =
        mockMvc
            .perform(get("/ship-data"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html)
        .contains("class=\"page-head\"")
        .contains("Avenger Titan")
        .doesNotContain("ship-data-reset-fitted")
        .doesNotContain("js-visibility-toggle")
        .doesNotContain("reset-fitted-confirm-modal");
  }

  /**
   * Empty catalogues show the empty state instead of a headless table.
   *
   * @throws Exception if the request fails
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void emptyCataloguesShowTheEmptyState() throws Exception {
    stubCatalogue(true);

    String html =
        mockMvc
            .perform(get("/ship-data"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(html.split("data-testid=\"empty-state\"", -1)).hasSize(3);
    assertThat(html).doesNotContain(" id=\"shipTypesTable\"").doesNotContain("colspan");
  }
}

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
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialMatrixItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialPriceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialPriceOverviewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.support.PageStylesheets;
import java.math.BigDecimal;
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
 * Render tests for the {@code /materials} listing and {@code /materials/{id}} detail pages: the
 * delegated filter and toggle bindings appear in the output and the page renders completely, which
 * fails if a Java list is inlined into the page script again.
 */
@SpringBootTest
class MaterialsPageControllerMvcTest {

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
  @WithMockUser
  void listMaterials_rendersCategoryToggleBindingAndCompletesScript() throws Exception {
    MaterialPriceOverviewDto dto =
        new MaterialPriceOverviewDto(
            UUID.randomUUID(),
            "Aluminum",
            new MaterialCategoryDto(UUID.randomUUID(), "Mineral", 0L),
            false,
            false,
            false,
            new BigDecimal("5.0"),
            new BigDecimal("7.0"));
    PageResponse<MaterialPriceOverviewDto> pageResponse =
        new PageResponse<>(List.of(dto), 0, 10000, 1, 1, List.of());

    when(backendApiClient.get(
            eq("/api/v1/materials/prices-overview?size=10000&sort=name,asc"), anyTypeRef()))
        .thenReturn(pageResponse);

    mockMvc
        .perform(get("/materials"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("src=\"/js/materials.js\"")))
        .andExpect(content().string(containsString("data-trigger=\"materials-toggle-grouping\"")))
        .andExpect(content().string(containsString("id=\"materialsGrouped\"")))
        .andExpect(content().string(containsString("id=\"materialsFlat\"")))
        .andExpect(content().string(containsString("</body>")))
        .andExpect(content().string(containsString("</html>")))
        .andExpect(content().string(containsString("<datalist id=\"materialNames-data\">")))
        .andExpect(content().string(containsString("<option value=\"Aluminum\">")))
        .andExpect(
            PageStylesheets.content(
                containsString(
                    ".form-group input:where(:not([type='checkbox']):not([type='radio']))")));
  }

  /**
   * The matrix-overview shell ({@code GET /materials/overview}) must render the category-grouping
   * toggle checkbox. The flat-vs-grouped switch itself is applied client-side by {@code
   * /js/materials-matrix.js}; this test only pins that the control the script binds to is present
   * in the shipped shell.
   */
  @Test
  @WithMockUser
  void getMatrixOverview_rendersGroupByCategoryToggle() throws Exception {
    PageResponse<MaterialMatrixItemDto> emptyPage =
        new PageResponse<>(List.of(), 0, 100000, 0, 0, List.of());
    when(backendApiClient.getCached(eq(CachedCatalog.MATERIALS_MATRIX), anyTypeRef()))
        .thenReturn(emptyPage);

    mockMvc
        .perform(get("/materials/overview"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"filterGroupByCategory\"")))
        .andExpect(content().string(containsString("</html>")));
  }

  /**
   * Same as {@link #listMaterials_rendersCategoryToggleBindingAndCompletesScript()} for the detail
   * page: the terminal filter binding is present and the page renders completely.
   */
  @Test
  @WithMockUser
  void getMaterialDetail_ShouldRenderFilterBinding_AfterDatalist() throws Exception {
    UUID id = UUID.randomUUID();
    MaterialDto material =
        new MaterialDto(
            id,
            "Aluminum",
            "RAW",
            "SCU",
            "Aluminum description",
            null,
            null,
            false,
            false,
            false,
            false,
            false,
            false,
            true,
            0L);
    MaterialPriceDto priceDto =
        new MaterialPriceDto(
            UUID.randomUUID(),
            "Area18",
            new BigDecimal("5.0"),
            new BigDecimal("7.0"),
            100,
            200,
            true,
            true);
    PageResponse<MaterialPriceDto> pricesPage =
        new PageResponse<>(List.of(priceDto), 0, 10000, 1, 1, List.of());

    when(backendApiClient.get(eq("/api/v1/materials/" + id), eq(MaterialDto.class)))
        .thenReturn(material);
    when(backendApiClient.get(
            eq("/api/v1/materials/" + id + "/prices?size=10000&sort=terminal.name,asc&page=0"),
            anyTypeRef()))
        .thenReturn(pricesPage);

    mockMvc
        .perform(get("/materials/" + id))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("src=\"/js/material-detail.js\"")))
        .andExpect(content().string(containsString("</body>")))
        .andExpect(content().string(containsString("</html>")))
        .andExpect(content().string(containsString("id=\"terminalNames-data\"")))
        .andExpect(content().string(containsString("value=\"Area18\"")))
        .andExpect(
            PageStylesheets.content(
                containsString(
                    ".form-group input:where(:not([type='checkbox']):not([type='radio']))")));
  }
}

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

package de.greluc.krt.profit.basetool.frontend.blueprint.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.blueprint.model.BlueprintOverviewEntryDto;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;
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
 * MVC render test for {@link BlueprintOverviewPageController}'s server-side pagination
 * (REQ-INV-013): the page-nav and the size picker render from a multi-page {@code PageResponse},
 * and their links keep the active search.
 */
@SpringBootTest
class BlueprintOverviewPageControllerMvcTest {

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
   * Builds a deterministic page envelope of {@code total} products as the mocked backend answer.
   *
   * @param pageIndex zero-based page index to report
   * @param pageSize page size to report
   * @param total total number of matching products to report
   * @return the mocked page envelope with {@code pageSize}-bounded content
   */
  private static PageResponse<BlueprintOverviewEntryDto> page(
      int pageIndex, int pageSize, int total) {
    List<BlueprintOverviewEntryDto> content =
        IntStream.range(0, Math.min(pageSize, total))
            .mapToObj(i -> new BlueprintOverviewEntryDto("product-" + i, "Product " + i, 1L))
            .toList();
    int totalPages = (int) Math.ceil((double) total / pageSize);
    return new PageResponse<>(content, pageIndex, pageSize, total, totalPages, List.of());
  }

  @Test
  @WithMockUser
  void view_multiPageResult_rendersPaginationAndSizePicker() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(page(1, 50, 120));

    mockMvc
        .perform(get("/blueprint-overview").param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(view().name("blueprint-overview"))
        .andExpect(content().string(containsString("/blueprint-overview?page=0&amp;size=50")))
        .andExpect(content().string(containsString("/blueprint-overview?page=2&amp;size=50")))
        .andExpect(content().string(containsString("/blueprint-overview?page=0&amp;size=10")))
        .andExpect(content().string(containsString("/blueprint-overview?page=0&amp;size=100")));
  }

  @Test
  @WithMockUser
  void view_withSearch_keepsSearchInPaginationLinks() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(page(0, 10, 25));

    mockMvc
        .perform(get("/blueprint-overview").param("search", "Aurora").param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(containsString("/blueprint-overview?search=Aurora&amp;page=1&amp;size=10")))
        .andExpect(
            content()
                .string(
                    containsString("/blueprint-overview?search=Aurora&amp;page=0&amp;size=50")));
  }

  @Test
  @WithMockUser
  void view_fragmentResults_rendersOnlyTableFragment() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(page(0, 50, 120));

    mockMvc
        .perform(get("/blueprint-overview").param("fragment", "results"))
        .andExpect(status().isOk())
        .andExpect(
            content().string(containsString("class=\"data-table data-table--stack bpo-table\"")))
        .andExpect(content().string(containsString("class=\"pagination\"")))
        .andExpect(content().string(not(containsString("id=\"bp-overview-results\""))))
        .andExpect(content().string(not(containsString("id=\"bp-overview-filter-form\""))));
  }

  /**
   * The list follows the list pattern: a page head with the „Flotte &amp; Logistik" eyebrow and the
   * total, one always-visible search, the owners column filled per row, no HUD box and no „Details"
   * button column.
   */
  @Test
  @WithMockUser
  void view_rendersTheListPattern() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(page(0, 50, 3));

    mockMvc
        .perform(get("/blueprint-overview").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-testid=\"page-head\"")))
        .andExpect(content().string(containsString("data-testid=\"page-eyebrow\"")))
        .andExpect(content().string(containsString("Flotte &amp; Logistik")))
        .andExpect(content().string(containsString("data-testid=\"page-head-count\"")))
        .andExpect(content().string(containsString("data-testid=\"toolbar-search\"")))
        .andExpect(content().string(containsString("data-testid=\"bp-overview-row\"")))
        .andExpect(content().string(containsString("data-product-key=\"product-0\"")))
        .andExpect(content().string(containsString("class=\"bpo-chips\"")))
        .andExpect(content().string(containsString("Kann craften")))
        .andExpect(content().string(not(containsString("hud-box"))))
        .andExpect(content().string(not(containsString("class=\"greeting"))))
        .andExpect(content().string(not(containsString("bp-filter"))))
        .andExpect(content().string(not(containsString("<details"))));
  }

  /** An empty list shows the shared empty state instead of a table row spanning the columns. */
  @Test
  @WithMockUser
  void view_emptyList_rendersTheEmptyState() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(page(0, 50, 0));

    mockMvc
        .perform(get("/blueprint-overview"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-testid=\"empty-state\"")))
        .andExpect(content().string(not(containsString("text-center"))))
        .andExpect(content().string(not(containsString("bpo-table"))));
  }

  @Test
  @WithMockUser
  void view_singleShortPage_rendersNeitherPageNavNorSizePicker() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class)))
        .thenReturn(page(0, 50, 5));

    mockMvc
        .perform(get("/blueprint-overview"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("class=\"pagination\""))))
        .andExpect(content().string(not(containsString("page-size-picker"))));
  }
}

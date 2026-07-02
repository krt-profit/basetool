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
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintOverviewEntryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
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
 * MVC-level render test for {@link BlueprintOverviewPageController}: pins the server-side
 * pagination surface of the availability page (REQ-INV-013) — the page-nav and the 10/50/100 size
 * picker render from the {@code PageResponse} envelope, and their links keep the active search.
 *
 * <p>Rendering a multi-page response is deliberately part of this test: the shared pagination
 * fragment used to call the non-existent {@code pageNumber()}/{@code pageSize()} accessors on the
 * {@code PageResponse} record, which made every page with {@code totalPages > 1} blow up at render
 * time. This test fails if that regression ever comes back.
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

  // covers REQ-INV-013 — multi-page result renders the page-nav and the size picker; the size
  // links jump back to page 0.
  @Test
  @WithMockUser
  void view_multiPageResult_rendersPaginationAndSizePicker() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page(1, 50, 120));

    mockMvc
        .perform(get("/blueprint-overview").param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(view().name("blueprint-overview"))
        // page-nav: previous-page link (page 0) and next-page link (page 2) both present
        .andExpect(content().string(containsString("/blueprint-overview?page=0&amp;size=50")))
        .andExpect(content().string(containsString("/blueprint-overview?page=2&amp;size=50")))
        // size picker: the two non-active sizes are links back to page 0
        .andExpect(content().string(containsString("/blueprint-overview?page=0&amp;size=10")))
        .andExpect(content().string(containsString("/blueprint-overview?page=0&amp;size=100")));
  }

  // covers REQ-INV-013 — paging and re-sizing keep the active search in every generated link.
  @Test
  @WithMockUser
  void view_withSearch_keepsSearchInPaginationLinks() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any())).thenReturn(page(0, 10, 25));

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

  // covers REQ-FE-002 — an AJAX swap request (fragment=results) renders only the inner table +
  // pagination fragment: the data table is present, but the surrounding page chrome (the filter
  // form and the swap-target wrapper div, both outside the fragment) is not.
  @Test
  @WithMockUser
  void view_fragmentResults_rendersOnlyTableFragment() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page(0, 50, 120));

    mockMvc
        .perform(get("/blueprint-overview").param("fragment", "results"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("class=\"data-table\"")))
        .andExpect(content().string(containsString("class=\"pagination\"")))
        .andExpect(content().string(not(containsString("id=\"bp-overview-results\""))))
        .andExpect(content().string(not(containsString("class=\"bp-filter\""))));
  }

  // covers REQ-INV-013 — a single short page needs neither page-nav nor size picker.
  @Test
  @WithMockUser
  void view_singleShortPage_rendersNeitherPageNavNorSizePicker() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page(0, 50, 5));

    mockMvc
        .perform(get("/blueprint-overview"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("class=\"pagination\""))))
        .andExpect(content().string(not(containsString("page-size-picker"))));
  }
}

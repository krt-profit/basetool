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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.RefineryOrderListDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC-level render test for the refinery-order-list pagination on {@link
 * RefineryOrderPageController} — pins REQ-REFINERY-019: {@code GET /refinery-orders} renders the
 * shared page-nav and the 10/50/100 size picker (REQ-INV-013 contract) from the {@code
 * PageResponse} envelope, and every generated link keeps the active status + {@code onlyMine}
 * filter.
 *
 * <p>The mocked backend returns an empty content page with inflated totals: pagination chrome is
 * driven by the page envelope, not the row content.
 */
@SpringBootTest
@ActiveProfiles("test")
class RefineryOrderPaginationMvcTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
  }

  /**
   * Builds a deterministic page envelope as the mocked backend answer. The content is intentionally
   * empty — only the page coordinates matter for the pagination chrome.
   *
   * @param pageIndex zero-based page index to report
   * @param pageSize page size to report
   * @param total total number of matching orders to report
   * @return the mocked page envelope with empty content and the derived total-pages count
   */
  private static PageResponse<RefineryOrderListDto> page(int pageIndex, int pageSize, int total) {
    int totalPages = (int) Math.ceil((double) total / pageSize);
    return new PageResponse<>(List.of(), pageIndex, pageSize, total, totalPages, List.of());
  }

  // covers REQ-REFINERY-019 — a multi-page result renders the page-nav (prev/next) and the
  // 10/50/100 size picker; both keep the default status filter, and size links jump back to page 0.
  @Test
  void viewOrders_multiPageResult_rendersPaginationAndSizePicker() throws Exception {
    when(backendApiClient.get(contains("/api/v1/refinery-orders/"), anyTypeRef()))
        .thenReturn(page(1, 50, 300));

    mockMvc
        .perform(get("/refinery-orders").param("page", "1").with(oauth2Login()))
        .andExpect(status().isOk())
        .andExpect(view().name("refinery-orders-index"))
        // page-nav: previous/first (page 0) and next (page 2), default status filter preserved
        .andExpect(
            content()
                .string(
                    containsString(
                        "/refinery-orders?status=OPEN&amp;status=IN_PROGRESS&amp;page=0&amp;size=50")))
        .andExpect(content().string(containsString("status=IN_PROGRESS&amp;page=2&amp;size=50")))
        // size picker: the two non-active sizes are links back to page 0
        .andExpect(content().string(containsString("status=IN_PROGRESS&amp;page=0&amp;size=10")))
        .andExpect(content().string(containsString("status=IN_PROGRESS&amp;page=0&amp;size=100")));
  }

  // covers REQ-REFINERY-019 — the onlyMine toggle is preserved in every paging/sizing link.
  @Test
  void viewOrders_onlyMine_keepsToggleInPaginationLinks() throws Exception {
    when(backendApiClient.get(contains("/api/v1/refinery-orders/"), anyTypeRef()))
        .thenReturn(page(1, 50, 300));

    mockMvc
        .perform(
            get("/refinery-orders")
                .param("onlyMine", "true")
                .param("page", "1")
                .with(oauth2Login()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("onlyMine=true&amp;page=2&amp;size=50")))
        .andExpect(content().string(containsString("onlyMine=true&amp;page=0&amp;size=10")));
  }

  // covers REQ-FE-005 — an AJAX swap request (fragment=results) renders only the inner table +
  // pagination fragment: the data table and page-nav are present, but the wrapper div and the
  // filter form (both outside the fragment) are not.
  @Test
  void viewOrders_fragmentResults_rendersOnlyTableFragment() throws Exception {
    when(backendApiClient.get(contains("/api/v1/refinery-orders/"), anyTypeRef()))
        .thenReturn(page(0, 50, 300));

    mockMvc
        .perform(get("/refinery-orders").param("fragment", "results").with(oauth2Login()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("class=\"data-table\"")))
        .andExpect(content().string(containsString("class=\"pagination\"")))
        .andExpect(content().string(not(containsString("id=\"refinery-orders-results\""))))
        .andExpect(content().string(not(containsString("id=\"refinery-filter-form\""))));
  }

  // covers REQ-REFINERY-019 — a single short page needs neither page-nav nor size picker.
  @Test
  void viewOrders_singleShortPage_rendersNeitherPageNavNorSizePicker() throws Exception {
    when(backendApiClient.get(contains("/api/v1/refinery-orders/"), anyTypeRef()))
        .thenReturn(page(0, 50, 5));

    mockMvc
        .perform(get("/refinery-orders").with(oauth2Login()))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("class=\"pagination\""))))
        .andExpect(content().string(not(containsString("page-size-picker"))));
  }
}

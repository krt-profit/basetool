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
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.config.SquadronContextAdvice;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC-level render test for the order-list pagination on {@link JobOrderPageController} — pins
 * REQ-ORDERS-020: {@code GET /orders} renders the shared page-nav and the order-specific 50/100/200
 * size picker from the {@code PageResponse} envelope, and every generated link keeps the active
 * status + scope filter. The order page deliberately deviates from the shared 10/50/100 contract
 * (REQ-INV-013) so the drag-reorder queue fits on one page in the common case.
 *
 * <p>The mocked backend returns an empty content page with inflated totals: pagination chrome is
 * driven by the page envelope, not the row content, so an empty list keeps the test focused on the
 * navigation surface.
 */
@SpringBootTest
@ActiveProfiles("test")
class JobOrderPaginationMvcTest {

  @Autowired private WebApplicationContext context;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    // Pass the profit gate so viewOrders renders the list instead of redirecting to /orders/create.
    when(backendApiClient.get(
            "/api/v1/me/capabilities", SquadronContextAdvice.CapabilitiesResponse.class))
        .thenReturn(new SquadronContextAdvice.CapabilitiesResponse(true, true));
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
  private static PageResponse<JobOrderDto> page(int pageIndex, int pageSize, int total) {
    int totalPages = (int) Math.ceil((double) total / pageSize);
    return new PageResponse<>(List.of(), pageIndex, pageSize, total, totalPages, List.of());
  }

  // covers REQ-ORDERS-020 — a multi-page result renders the page-nav (prev/next) and the 50/100/200
  // size picker; both keep the default status + scope filter, and size links jump back to page 0.
  @Test
  @WithMockUser
  void viewOrders_multiPageResult_rendersPaginationAndSizePicker() throws Exception {
    when(backendApiClient.get(contains("/api/v1/orders?"), anyTypeRef()))
        .thenReturn(page(1, 100, 300));

    mockMvc
        .perform(get("/orders").param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(view().name("orders-index"))
        // page-nav: previous/first (page 0) and next/last (page 2), default filter preserved
        .andExpect(
            content()
                .string(
                    containsString(
                        "/orders?status=OPEN&amp;status=IN_PROGRESS&amp;scope=mine&amp;page=0&amp;size=100")))
        .andExpect(content().string(containsString("scope=mine&amp;page=2&amp;size=100")))
        // size picker: the two non-active sizes are links back to page 0
        .andExpect(content().string(containsString("scope=mine&amp;page=0&amp;size=50")))
        .andExpect(content().string(containsString("scope=mine&amp;page=0&amp;size=200")));
  }

  // covers REQ-ORDERS-020 — paging and re-sizing keep the active status filter in every link.
  @Test
  @WithMockUser
  void viewOrders_withStatusFilter_keepsFilterInPaginationLinks() throws Exception {
    when(backendApiClient.get(contains("/api/v1/orders?"), anyTypeRef()))
        .thenReturn(page(1, 100, 300));

    mockMvc
        .perform(get("/orders").param("status", "COMPLETED").param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(
                        "/orders?status=COMPLETED&amp;scope=mine&amp;page=2&amp;size=100")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "/orders?status=COMPLETED&amp;scope=mine&amp;page=0&amp;size=50")));
  }

  // covers REQ-FE-005 — an AJAX swap request (fragment=results) renders only the inner table +
  // pagination fragment: the data table and the page-nav are present, but the surrounding chrome
  // (the swap-target wrapper div and the filter form, both outside the fragment) is not.
  @Test
  @WithMockUser
  void viewOrders_fragmentResults_rendersOnlyTableFragment() throws Exception {
    when(backendApiClient.get(contains("/api/v1/orders?"), anyTypeRef()))
        .thenReturn(page(0, 100, 300));

    mockMvc
        .perform(get("/orders").param("fragment", "results"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("class=\"data-table\"")))
        .andExpect(content().string(containsString("class=\"pagination\"")))
        .andExpect(content().string(not(containsString("id=\"orders-results\""))))
        .andExpect(content().string(not(containsString("id=\"orders-filter-form\""))));
  }

  // covers REQ-ORDERS-020 — a single short page needs neither page-nav nor size picker.
  @Test
  @WithMockUser
  void viewOrders_singleShortPage_rendersNeitherPageNavNorSizePicker() throws Exception {
    when(backendApiClient.get(contains("/api/v1/orders?"), anyTypeRef()))
        .thenReturn(page(0, 100, 5));

    mockMvc
        .perform(get("/orders"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("class=\"pagination\""))))
        .andExpect(content().string(not(containsString("page-size-picker"))));
  }
}

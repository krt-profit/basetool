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

package de.greluc.krt.profit.basetool.frontend.refinery.web;

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oauth2Login;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.refinery.model.RefineryOrderListDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC render test for the refinery-order list pagination on {@link RefineryOrderPageController}
 * (REQ-REFINERY-019): the page nav and size picker render from the {@code PageResponse} envelope
 * and every link keeps the segment, {@code onlyMine} and search filters; every segment is one
 * backend page of the requested size.
 */
@SpringBootTest
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
   * Builds a mocked page envelope with empty content; only the page coordinates matter.
   *
   * @param pageIndex zero-based page index to report
   * @param pageSize page size to report
   * @param total total number of matching orders to report
   * @return the page envelope with empty content and the derived total-pages count
   */
  private static PageResponse<RefineryOrderListDto> page(int pageIndex, int pageSize, int total) {
    int totalPages = (int) Math.ceil((double) total / pageSize);
    return new PageResponse<>(List.of(), pageIndex, pageSize, total, totalPages, List.of());
  }

  /**
   * Builds a page envelope holding one completed order.
   *
   * @param total total number of matching orders to report
   * @return the page envelope with one row
   */
  private static PageResponse<RefineryOrderListDto> pageWithRow(int total) {
    RefineryOrderListDto order =
        new RefineryOrderListDto(
            UUID.fromString("00000000-0000-0000-0000-000000001042"),
            null,
            null,
            null,
            Instant.parse("2026-10-01T10:00:00Z"),
            60L,
            0d,
            0d,
            0d,
            0d,
            null,
            "COMPLETED",
            List.of(),
            null,
            1L);
    int totalPages = (int) Math.ceil((double) total / 50);
    return new PageResponse<>(List.of(order), 0, 50, total, totalPages, List.of());
  }

  @Test
  void viewOrders_multiPageResult_rendersPaginationAndSizePicker() throws Exception {
    when(backendApiClient.get(
            contains("/api/v1/refinery-orders/"), anyTypeRef(), any(Object[].class)))
        .thenReturn(page(1, 50, 300));

    mockMvc
        .perform(
            get("/refinery-orders")
                .param("view", "COMPLETED")
                .param("page", "1")
                .with(oauth2Login()))
        .andExpect(status().isOk())
        .andExpect(view().name("refinery-orders-index"))
        .andExpect(
            content()
                .string(containsString("/refinery-orders?view=COMPLETED&amp;page=0&amp;size=50")))
        .andExpect(content().string(containsString("view=COMPLETED&amp;page=2&amp;size=50")))
        .andExpect(content().string(containsString("view=COMPLETED&amp;page=0&amp;size=10")))
        .andExpect(content().string(containsString("view=COMPLETED&amp;page=0&amp;size=100")));
  }

  @Test
  void viewOrders_onlyMine_keepsToggleInPaginationLinks() throws Exception {
    when(backendApiClient.get(
            contains("/api/v1/refinery-orders/"), anyTypeRef(), any(Object[].class)))
        .thenReturn(page(1, 50, 300));

    mockMvc
        .perform(
            get("/refinery-orders")
                .param("view", "COMPLETED")
                .param("onlyMine", "true")
                .param("page", "1")
                .with(oauth2Login()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("onlyMine=true&amp;page=2&amp;size=50")))
        .andExpect(content().string(containsString("onlyMine=true&amp;page=0&amp;size=10")));
  }

  @Test
  void viewOrders_search_pagesOnTheBackendAndKeepsTheSearchInPaginationLinks() throws Exception {
    when(backendApiClient.get(
            contains("/api/v1/refinery-orders/"), anyTypeRef(), any(Object[].class)))
        .thenReturn(page(0, 1, 300));
    when(backendApiClient.get(
            contains("/api/v1/refinery-orders/"), anyTypeRef(), eq(1), eq(50), eq("Gold Erz")))
        .thenReturn(page(1, 50, 300));

    mockMvc
        .perform(
            get("/refinery-orders")
                .param("view", "RUNNING")
                .param("q", "Gold Erz")
                .param("page", "1")
                .with(oauth2Login()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("q=Gold%20Erz&amp;page=2&amp;size=50")))
        .andExpect(content().string(containsString("q=Gold%20Erz&amp;page=0&amp;size=10")));

    verify(backendApiClient)
        .get(
            eq(
                "/api/v1/refinery-orders/all?page={page}&size={size}&sort=endsAt,asc"
                    + "&status=OPEN,IN_PROGRESS&q={q}"),
            anyTypeRef(),
            eq(1),
            eq(50),
            eq("Gold Erz"));
    verify(backendApiClient, never())
        .get(contains("/api/v1/refinery-orders/"), anyTypeRef(), any(), eq(1000), any());
  }

  @Test
  void viewOrders_unknownPageSize_fallsBackToTheDefaultBackendPage() throws Exception {
    when(backendApiClient.get(
            contains("/api/v1/refinery-orders/"), anyTypeRef(), any(Object[].class)))
        .thenReturn(page(0, 50, 5));

    mockMvc
        .perform(
            get("/refinery-orders").param("view", "ALL").param("size", "1000").with(oauth2Login()))
        .andExpect(status().isOk());

    verify(backendApiClient)
        .get(
            eq(
                "/api/v1/refinery-orders/all?page={page}&size={size}&sort=startedAt,desc"
                    + "&status=OPEN,IN_PROGRESS,COMPLETED,CANCELED"),
            anyTypeRef(),
            eq(0),
            eq(50));
    verify(backendApiClient, never())
        .get(contains("/api/v1/refinery-orders/"), anyTypeRef(), any(), eq(1000));
  }

  @Test
  void viewOrders_fragmentResults_rendersOnlyTableFragment() throws Exception {
    when(backendApiClient.get(
            contains("/api/v1/refinery-orders/"), anyTypeRef(), any(Object[].class)))
        .thenReturn(pageWithRow(300));

    mockMvc
        .perform(
            get("/refinery-orders")
                .param("view", "COMPLETED")
                .param("fragment", "results")
                .with(oauth2Login()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("class=\"data-table data-table--stack")))
        .andExpect(content().string(containsString("class=\"pagination\"")))
        .andExpect(content().string(containsString("data-refinery-counts")))
        .andExpect(content().string(not(containsString("id=\"refinery-orders-results\""))))
        .andExpect(content().string(not(containsString("id=\"refinery-filter-form\""))));
  }

  @Test
  void viewOrders_singleShortPage_rendersNeitherPageNavNorSizePicker() throws Exception {
    when(backendApiClient.get(
            contains("/api/v1/refinery-orders/"), anyTypeRef(), any(Object[].class)))
        .thenReturn(page(0, 50, 5));

    mockMvc
        .perform(get("/refinery-orders").with(oauth2Login()))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("class=\"pagination\""))))
        .andExpect(content().string(not(containsString("page-size-picker"))));
  }
}

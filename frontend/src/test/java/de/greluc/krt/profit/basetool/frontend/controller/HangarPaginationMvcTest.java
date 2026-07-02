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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.ShipDto;
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
 * Render-level coverage for the personal hangar's server-side pagination + search (REQ-HANGAR-002),
 * mirroring the order/refinery pagination tests added in #772. The {@code BackendApiClient} is
 * mocked with a deterministic {@link PageResponse} envelope (content intentionally empty — the
 * pagination chrome is driven by the envelope, not the rows), so these assertions verify the
 * frontend renders the shared pagination component, snaps an out-of-set size to the default, clamps
 * a negative page, and threads the active {@code search} term through every page/size link.
 */
@SpringBootTest
@ActiveProfiles("test")
class HangarPaginationMvcTest {

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

  /** Builds a deterministic ship page envelope; the content list is intentionally empty. */
  private static PageResponse<ShipDto> page(int pageIndex, int pageSize, int total) {
    int totalPages = (int) Math.ceil((double) total / pageSize);
    return new PageResponse<>(List.of(), pageIndex, pageSize, total, totalPages, List.of());
  }

  @Test
  @WithMockUser
  void multiPageResult_rendersPaginationAndSizePicker() throws Exception {
    when(backendApiClient.get(contains("/api/v1/hangar/my-ships"), anyTypeRef()))
        .thenReturn(page(1, 50, 300));

    mockMvc
        .perform(get("/hangar").param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(view().name("hangar"))
        // page-nav: first/prev jump to page 0, next to page 2, all at the active size 50
        .andExpect(content().string(containsString("/hangar?page=0&amp;size=50")))
        .andExpect(content().string(containsString("/hangar?page=2&amp;size=50")))
        // size picker: 10/50/100, the inactive sizes re-enter at page 0
        .andExpect(content().string(containsString("page-size-picker")))
        .andExpect(content().string(containsString("/hangar?page=0&amp;size=10")))
        .andExpect(content().string(containsString("/hangar?page=0&amp;size=100")));
  }

  @Test
  @WithMockUser
  void withSearch_keepsSearchInPaginationLinksAndOffersClear() throws Exception {
    when(backendApiClient.get(contains("/api/v1/hangar/my-ships"), anyTypeRef()))
        .thenReturn(page(1, 50, 300));

    mockMvc
        .perform(get("/hangar").param("search", "Cutlass").param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(view().name("hangar"))
        // the active filter rides along on every page/size link
        .andExpect(
            content().string(containsString("/hangar?search=Cutlass&amp;page=2&amp;size=50")))
        .andExpect(
            content().string(containsString("/hangar?search=Cutlass&amp;page=0&amp;size=10")))
        // the clear-filter link drops the search but keeps the active page size
        .andExpect(content().string(containsString("href=\"/hangar?size=50\"")));
  }

  @Test
  @WithMockUser
  void singleShortPage_rendersNeitherPageNavNorSizePicker() throws Exception {
    when(backendApiClient.get(contains("/api/v1/hangar/my-ships"), anyTypeRef()))
        .thenReturn(page(0, 50, 3));

    mockMvc
        .perform(get("/hangar"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("class=\"pagination\""))))
        .andExpect(content().string(not(containsString("page-size-picker"))));
  }

  @Test
  @WithMockUser
  void unsupportedSize_snapsToDefaultBeforeReachingBackend() throws Exception {
    when(backendApiClient.get(contains("/api/v1/hangar/my-ships"), anyTypeRef()))
        .thenReturn(page(0, 50, 0));

    mockMvc.perform(get("/hangar").param("size", "5000")).andExpect(status().isOk());

    // A crafted ?size= outside 10/50/100 must never reach the backend as an unbounded page.
    verify(backendApiClient).get(eq("/api/v1/hangar/my-ships?page=0&size=50"), anyTypeRef());
  }

  @Test
  @WithMockUser
  void negativePage_clampsToZeroBeforeReachingBackend() throws Exception {
    when(backendApiClient.get(contains("/api/v1/hangar/my-ships"), anyTypeRef()))
        .thenReturn(page(0, 50, 0));

    mockMvc.perform(get("/hangar").param("page", "-1")).andExpect(status().isOk());

    verify(backendApiClient).get(eq("/api/v1/hangar/my-ships?page=0&size=50"), anyTypeRef());
  }

  @Test
  @WithMockUser
  void fragmentResults_rendersPaginationInsideTheSwapFragment() throws Exception {
    // The pagination controls live INSIDE the hangarResults fragment so an in-place filter/page
    // change re-renders them (REQ-HANGAR-002).
    when(backendApiClient.get(contains("/api/v1/hangar/my-ships"), anyTypeRef()))
        .thenReturn(page(1, 50, 300));

    mockMvc
        .perform(get("/hangar").param("fragment", "results"))
        .andExpect(status().isOk())
        .andExpect(view().name("hangar :: hangarResults"))
        .andExpect(content().string(containsString("class=\"pagination\"")))
        // the modals live outside the fragment and must not appear in the swap body
        .andExpect(content().string(not(containsString("id=\"ship-modal\""))));
  }
}

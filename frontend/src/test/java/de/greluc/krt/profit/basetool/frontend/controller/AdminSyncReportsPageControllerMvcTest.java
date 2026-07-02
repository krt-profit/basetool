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
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.SyncReportDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SyncReportPurgeResultDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.time.Instant;
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
 * MVC-level render test for {@link AdminSyncReportsPageController}: pins the AJAX pager fragment
 * (REQ-FE-002). The full page renders the swap-target wrapper + tab bar; {@code fragment=results}
 * renders only the inner table + pager block (tabs, total and purge form live outside it), and the
 * pager links keep the active tab's base path. Fails if the shared fragment selector breaks.
 */
@SpringBootTest
class AdminSyncReportsPageControllerMvcTest {

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
   * Stubs the backend with a two-page sync-report envelope so the table row and the pager both
   * render.
   *
   * @return a one-row, two-page envelope
   */
  private PageResponse<SyncReportDto> twoPages() {
    SyncReportDto ev =
        new SyncReportDto(
            UUID.randomUUID(),
            UUID.randomUUID(),
            Instant.parse("2026-05-28T00:00:00Z"),
            "SCWIKI",
            "CREATED_WIKI_ONLY",
            "commodity",
            null,
            null,
            "FragmentEvent",
            "detail");
    return new PageResponse<>(List.of(ev), 0, 50, 60L, 2, List.of());
  }

  // covers REQ-FE-002 — the full page renders the swap-target wrapper and the tab bar.
  @Test
  @WithMockUser(roles = "ADMIN")
  void combined_fullPage_rendersSwapWrapperAndTabs() throws Exception {
    when(backendApiClient.get(contains("/api/v1/sync-reports"), anyTypeRef()))
        .thenReturn(twoPages());

    mockMvc
        .perform(get("/admin/sync-reports"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/sync-reports"))
        .andExpect(content().string(containsString("id=\"sync-results\"")))
        .andExpect(content().string(containsString("tab-bar")));
  }

  // covers REQ-FE-002 — fragment=results on a source tab renders only the inner table + pager: the
  // row and pager are present and the pager keeps the active tab's base path, but the wrapper, tab
  // bar and purge form (all outside the fragment) are not.
  @Test
  @WithMockUser(roles = "ADMIN")
  void uex_fragmentResults_rendersOnlyInnerFragment_withTabBasePath() throws Exception {
    when(backendApiClient.get(contains("/api/v1/sync-reports"), anyTypeRef()))
        .thenReturn(twoPages());

    mockMvc
        .perform(get("/admin/sync-reports/uex").param("fragment", "results"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/sync-reports :: results"))
        .andExpect(content().string(containsString("FragmentEvent")))
        .andExpect(content().string(containsString("class=\"pager\"")))
        // The pager keeps the UEX tab's base path (shared render relays basePath into the
        // fragment).
        .andExpect(content().string(containsString("/admin/sync-reports/uex?page=1")))
        .andExpect(content().string(not(containsString("id=\"sync-results\""))))
        .andExpect(content().string(not(containsString("tab-bar"))))
        .andExpect(content().string(not(containsString("id=\"purge-form\""))));
  }

  // covers #582 — the delete-old twin (X-Requested-With) purges and returns the deleted-row count
  // as
  // JSON so the page shows a count toast in place rather than reloading.
  @Test
  @WithMockUser(roles = "ADMIN")
  void deleteOldAjax_withHeader_returns200WithDeletedCount() throws Exception {
    when(backendApiClient.delete(contains("/sync-reports"), eq(SyncReportPurgeResultDto.class)))
        .thenReturn(new SyncReportPurgeResultDto(5));

    mockMvc
        .perform(
            post("/admin/sync-reports/delete-old")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("days", "30")
                .param("source", ""))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("deleted")));
  }

  // covers #582 — days < 1 is rejected with 400 before any backend call.
  @Test
  @WithMockUser(roles = "ADMIN")
  void deleteOldAjax_withHeaderDaysZero_returns400() throws Exception {
    mockMvc
        .perform(
            post("/admin/sync-reports/delete-old")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("days", "0"))
        .andExpect(status().isBadRequest());
  }

  // covers #582 — header routing: the same URL WITHOUT the header still hits the classic form
  // handler and redirects (no-JS fallback preserved).
  @Test
  @WithMockUser(roles = "ADMIN")
  void deleteOld_withoutHeader_redirects() throws Exception {
    when(backendApiClient.delete(contains("/sync-reports"), eq(SyncReportPurgeResultDto.class)))
        .thenReturn(new SyncReportPurgeResultDto(5));

    mockMvc
        .perform(post("/admin/sync-reports/delete-old").with(csrf()).param("days", "30"))
        .andExpect(status().is3xxRedirection());
  }
}

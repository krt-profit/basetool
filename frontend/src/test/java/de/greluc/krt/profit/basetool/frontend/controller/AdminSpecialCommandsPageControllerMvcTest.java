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
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * MVC-level render test for {@link AdminSpecialCommandsPageController}: pins the AJAX swap fragment
 * (REQ-FE-002) of the include-inactive filter. The full page renders the swap-target wrapper; the
 * {@code fragment=results} request renders only the inner SK-list block (the toolbar, modal and
 * wrapper live outside it). Fails if the fragment selector breaks.
 */
@SpringBootTest
class AdminSpecialCommandsPageControllerMvcTest {

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
   * Stubs the backend SK catalogue with one active special command so the list table renders.
   *
   * @return a single-row page envelope in the raw {@code Map} wire shape the controller parses
   */
  private PageResponse<Map<String, Object>> oneSpecialCommand() {
    Map<String, Object> sc = new HashMap<>();
    sc.put("id", UUID.randomUUID().toString());
    sc.put("name", "Fragment SK");
    sc.put("shorthand", "FSK");
    sc.put("description", "desc");
    sc.put("active", true);
    sc.put("isProfitEligible", false);
    sc.put("version", 0);
    return new PageResponse<>(List.of(sc), 0, 1000, 1L, 1, List.of());
  }

  // covers REQ-FE-002 — the full page renders the swap-target wrapper.
  @Test
  @WithMockUser(roles = "ADMIN")
  void list_fullPage_rendersSwapWrapper() throws Exception {
    when(backendApiClient.get(contains("/api/v1/special-commands"), anyTypeRef()))
        .thenReturn(oneSpecialCommand());

    mockMvc
        .perform(get("/admin/special-commands"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/special-commands"))
        .andExpect(content().string(containsString("id=\"sc-results\"")));
  }

  // covers REQ-FE-002 — fragment=results renders only the inner SK-list block: the row is present,
  // but the swap-target wrapper, the create modal and the toolbar button (all outside the fragment)
  // are not.
  @Test
  @WithMockUser(roles = "ADMIN")
  void list_fragmentResults_rendersOnlyInnerFragment() throws Exception {
    when(backendApiClient.get(contains("/api/v1/special-commands"), anyTypeRef()))
        .thenReturn(oneSpecialCommand());

    mockMvc
        .perform(get("/admin/special-commands").param("fragment", "results"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/special-commands :: results"))
        .andExpect(content().string(containsString("Fragment SK")))
        .andExpect(content().string(not(containsString("id=\"sc-results\""))))
        .andExpect(content().string(not(containsString("id=\"specialcommand-modal\""))))
        .andExpect(content().string(not(containsString("id=\"add-sc-btn\""))));
  }

  // covers #582 — the SK-create twin (X-Requested-With + form params) relays to the backend and
  // returns 200; the list page re-swaps the SK-list fragment.
  @Test
  @WithMockUser(roles = "ADMIN")
  void createSpecialCommandAjax_withHeader_returns200() throws Exception {
    when(backendApiClient.post(contains("/special-commands"), any(), eq(Void.class)))
        .thenReturn(null);

    mockMvc
        .perform(
            post("/admin/special-commands")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("name", "New SK")
                .param("shorthand", "NSK")
                .param("description", "d")
                .param("version", "0"))
        .andExpect(status().isOk());
  }

  // covers #582 — the member lead-toggle twin (X-Requested-With + isLead/version) PATCHes the lead
  // flag and returns 200.
  @Test
  @WithMockUser(roles = "ADMIN")
  void toggleMemberLeadAjax_withHeader_returns200() throws Exception {
    UUID skId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    when(backendApiClient.patch(contains("/lead"), any(), eq(Void.class))).thenReturn(null);

    mockMvc
        .perform(
            post("/admin/special-commands/" + skId + "/members/" + userId + "/lead")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("isLead", "true")
                .param("version", "0"))
        .andExpect(status().isOk());
  }

  // covers #582 / REQ-FE-005 — the NEW member-roster fragment GET renders only the membersResults
  // fragment; the add-member modal (which lives outside the fragment) is not present.
  @Test
  @WithMockUser(roles = "ADMIN")
  void detail_fragmentMembers_rendersOnlyMembersFragment() throws Exception {
    UUID skId = UUID.randomUUID();
    Map<String, Object> sc = new HashMap<>();
    sc.put("id", skId.toString());
    sc.put("name", "Detail SK");
    sc.put("shorthand", "DSK");
    sc.put("description", "desc");
    sc.put("active", true);
    sc.put("isProfitEligible", false);
    sc.put("version", 0);
    when(backendApiClient.get(eq("/api/v1/special-commands/" + skId), anyTypeRef())).thenReturn(sc);
    when(backendApiClient.get(eq("/api/v1/special-commands/" + skId + "/members"), anyTypeRef()))
        .thenReturn(List.of());

    mockMvc
        .perform(get("/admin/special-commands/" + skId).param("fragment", "members"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/special-command-detail :: membersResults"))
        .andExpect(content().string(not(containsString("add-member-modal"))));
  }

  // covers #582 — header routing: the same SK-create URL WITHOUT the header still hits the classic
  // form handler and redirects (no-JS fallback preserved).
  @Test
  @WithMockUser(roles = "ADMIN")
  void createSpecialCommand_withoutHeader_redirects() throws Exception {
    when(backendApiClient.post(contains("/special-commands"), any(), eq(Void.class)))
        .thenReturn(null);

    mockMvc
        .perform(
            post("/admin/special-commands")
                .with(csrf())
                .param("name", "New SK")
                .param("shorthand", "NSK")
                .param("description", "d")
                .param("version", "0"))
        .andExpect(status().is3xxRedirection());
  }
}

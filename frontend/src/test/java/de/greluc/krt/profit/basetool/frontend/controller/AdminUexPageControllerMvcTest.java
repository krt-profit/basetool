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

import static org.hamcrest.Matchers.containsString;
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

import de.greluc.krt.profit.basetool.frontend.model.dto.TerminalDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
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
 * MVC-level test for {@link AdminUexPageController}'s in-place AJAX twins (epic #571 / #582).
 * Proves the {@code X-Requested-With} header routing: the terminal toggle-visibility and the
 * loading-dock override twins are {@code @ResponseBody} and return {@code 200} on success, an
 * unknown action is rejected with {@code 400}, and the same loading-dock URL without the header
 * still hits the classic redirect handler. Fails if the header gating or the action whitelist
 * breaks.
 */
@SpringBootTest
class AdminUexPageControllerMvcTest {

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
   * Builds a terminal DTO in the backend wire shape so the toggle-visibility twin can re-emit the
   * UEX-imported display fields verbatim in the PUT body.
   *
   * @param id the terminal id
   * @return a fully-populated {@link TerminalDto}
   */
  private TerminalDto terminal(UUID id) {
    return new TerminalDto(
        id,
        "Lorville TDD",
        "TDD",
        "Stanton",
        "Hurston",
        "Lorville",
        null,
        true,
        false,
        false,
        false,
        true,
        false,
        null,
        false);
  }

  // covers the .form-group checkbox regression class (PR #1405) — the page-scoped .form-group
  // input rule ties the global KRT square-checkbox rule at (0,1,1) and, rendering after
  // styles.css, would win and stretch any .form-group checkbox/radio into a full-width padded
  // bar. Pins the :where() exclusion so the page rule can never capture checkbox/radio inputs.
  // The GET handler degrades to the error banner on unstubbed fetches, still rendering the head.
  @Test
  @WithMockUser(roles = "ADMIN")
  void listData_ShouldExcludeCheckboxesFromFormGroupInputRule() throws Exception {
    mockMvc
        .perform(get("/admin/uex-data"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(
                        ".form-group input:where(:not([type='checkbox']):not([type='radio']))")));
  }

  // covers #582 — the terminal toggle-visibility twin (X-Requested-With) flips the hidden flag off
  // a freshly-read record and returns 200; the button is re-rendered client-side.
  @Test
  @WithMockUser(roles = "ADMIN")
  void toggleTerminalVisibilityAjax_withHeader_returns200() throws Exception {
    UUID id = UUID.randomUUID();
    when(backendApiClient.get(eq("/api/v1/terminals/" + id), eq(TerminalDto.class)))
        .thenReturn(terminal(id));
    when(backendApiClient.put(eq("/api/v1/terminals/" + id), any(), eq(Void.class)))
        .thenReturn(null);

    mockMvc
        .perform(
            post("/admin/uex-data/terminals/" + id + "/toggle-visibility")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isOk());
  }

  // covers #582 — the loading-dock override twin (X-Requested-With + action=yes) PATCHes the pin
  // and
  // returns 200.
  @Test
  @WithMockUser(roles = "ADMIN")
  void loadingDockOverrideAjax_withHeaderYes_returns200() throws Exception {
    UUID id = UUID.randomUUID();
    when(backendApiClient.patch(contains("loading-dock"), any(), eq(Void.class))).thenReturn(null);

    mockMvc
        .perform(
            post("/admin/uex-data/cities/" + id + "/loading-dock")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("action", "yes"))
        .andExpect(status().isOk());
  }

  // covers #582 — the action whitelist: a bogus action is rejected with 400.
  @Test
  @WithMockUser(roles = "ADMIN")
  void loadingDockOverrideAjax_withHeaderBogusAction_returns400() throws Exception {
    UUID id = UUID.randomUUID();

    mockMvc
        .perform(
            post("/admin/uex-data/cities/" + id + "/loading-dock")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .param("action", "bogus"))
        .andExpect(status().isBadRequest());
  }

  // covers #582 — header routing: the same loading-dock URL WITHOUT the header still hits the
  // classic form handler and redirects (no-JS fallback preserved).
  @Test
  @WithMockUser(roles = "ADMIN")
  void loadingDockOverride_withoutHeader_redirects() throws Exception {
    UUID id = UUID.randomUUID();
    when(backendApiClient.patch(contains("loading-dock"), any(), eq(Void.class))).thenReturn(null);

    mockMvc
        .perform(
            post("/admin/uex-data/cities/" + id + "/loading-dock")
                .with(csrf())
                .param("action", "yes"))
        .andExpect(status().is3xxRedirection());
  }
}

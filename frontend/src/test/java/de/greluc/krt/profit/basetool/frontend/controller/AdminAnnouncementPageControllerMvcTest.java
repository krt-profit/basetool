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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * MVC-level test for {@link AdminAnnouncementPageController}'s in-place AJAX twins (epic #571 /
 * #582). Proves the {@code X-Requested-With} header routing: the update/delete twins are
 * {@code @ResponseBody} and return {@code 200} (the update echoing the re-fetched optimistic-lock
 * {@code version}), while the same URL POSTed without the header still hits the classic redirect
 * handler. Fails if the header gating or the version write-back breaks.
 */
@SpringBootTest
class AdminAnnouncementPageControllerMvcTest {

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

  // covers #582 — the update twin (X-Requested-With + JSON body) persists, then re-reads the admin
  // record and returns the bumped version so the page can write it back into the hidden input.
  @Test
  @WithMockUser(roles = "ADMIN")
  void updateAjax_withHeader_returns200AndEchoesVersion() throws Exception {
    when(backendApiClient.put(eq("/api/v1/announcement"), any(), eq(Void.class))).thenReturn(null);
    when(backendApiClient.get(contains("/announcement/admin"), anyTypeRef()))
        .thenReturn(Map.of("content", "x", "version", 7));

    mockMvc
        .perform(
            post("/admin/announcement/update")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"x\",\"version\":0}"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("version")))
        .andExpect(content().string(containsString("7")));
  }

  // covers #582 — the delete twin (X-Requested-With) returns 200 so the page clears the form in
  // place rather than reloading.
  @Test
  @WithMockUser(roles = "ADMIN")
  void deleteAjax_withHeader_returns200() throws Exception {
    when(backendApiClient.delete(eq("/api/v1/announcement"), eq(Void.class))).thenReturn(null);

    mockMvc
        .perform(
            post("/admin/announcement/delete")
                .header("X-Requested-With", "XMLHttpRequest")
                .with(csrf()))
        .andExpect(status().isOk());
  }

  // covers #582 — header routing: the same update URL WITHOUT the header still hits the classic
  // form handler and redirects (no-JS fallback preserved).
  @Test
  @WithMockUser(roles = "ADMIN")
  void update_withoutHeader_redirects() throws Exception {
    when(backendApiClient.put(eq("/api/v1/announcement"), any(), eq(Void.class))).thenReturn(null);

    mockMvc
        .perform(post("/admin/announcement/update").with(csrf()).param("content", "x"))
        .andExpect(status().is3xxRedirection());
  }
}

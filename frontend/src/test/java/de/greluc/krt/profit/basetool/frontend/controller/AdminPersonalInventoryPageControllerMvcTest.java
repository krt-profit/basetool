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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalInventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Verifies admin role enforcement for {@link AdminPersonalInventoryPageController}: a regular user
 * must be denied access (403), while an ADMIN gets the admin view rendered. Backend calls are
 * mocked because this test focuses on routing and security, not backend behavior.
 */
@SpringBootTest
class AdminPersonalInventoryPageControllerMvcTest {

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

  @Test
  @WithMockUser(roles = "USER")
  void view_shouldDenyAccess_whenUserIsNotAdmin() throws Exception {
    mockMvc.perform(get("/admin/personal-inventory")).andExpect(status().isForbidden());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void view_shouldRenderAdminView_whenUserIsAdmin() throws Exception {
    // Given
    PageResponse<UserDto> users = new PageResponse<>(List.of(), 0, 1000, 0, 1, List.of());
    PageResponse<PersonalInventoryItemDto> empty =
        new PageResponse<>(List.of(), 0, 50, 0, 0, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(users).thenReturn(empty);

    // When & Then
    mockMvc
        .perform(get("/admin/personal-inventory"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/personal-inventory"));
  }

  // covers REQ-FE-002 — an AJAX swap (fragment=results) for a selected member renders only the
  // item-list fragment (the member <select> and admin banner live outside it) and skips the
  // (up to 1000-row) user-list fetch the full page does.
  @Test
  @WithMockUser(roles = "ADMIN")
  void view_fragmentResults_rendersOnlyResultsFragment_andSkipsUserListFetch() throws Exception {
    String userSub = UUID.randomUUID().toString();
    PageResponse<PersonalInventoryItemDto> items =
        new PageResponse<>(List.of(), 0, 50, 0, 0, List.of());
    when(backendApiClient.get(contains("/api/v1/admin/personal-inventory/"), anyTypeRef()))
        .thenReturn(items);

    mockMvc
        .perform(
            get("/admin/personal-inventory").param("userSub", userSub).param("fragment", "results"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/personal-inventory :: results"))
        .andExpect(content().string(containsString("krt-pi-table")))
        // Member dropdown, banner and the swap-target wrapper are outside the fragment.
        .andExpect(content().string(not(containsString("krt-pi-userform"))))
        .andExpect(content().string(not(containsString("id=\"pi-results\""))))
        .andExpect(content().string(not(containsString("krt-admin-banner"))));

    // The fragment path must not query the user list.
    verify(backendApiClient, never()).get(eq("/api/v1/users?size=1000"), anyTypeRef());
  }

  // Regression guard for the frontend-proxy double-encoding sub-class: the admin personal-inventory
  // item filter must forward a multi-word free-text term as a WebClient URI-template variable
  // ({q}),
  // not URLEncoder it into the URI string, so the backend @RequestParam decodes the exact typed
  // term. URLEncoder form-encoding (space -> '+') double-encodes across the frontend->backend hop
  // and yields zero matches. fragment=results skips the selected-member lookup, leaving one read.
  @Test
  @WithMockUser(roles = "ADMIN")
  void view_passesMultiWordQueryAsUriVariable() throws Exception {
    String userSub = UUID.randomUUID().toString();
    PageResponse<PersonalInventoryItemDto> items =
        new PageResponse<>(List.of(), 0, 50, 0, 0, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef(), any())).thenReturn(items);

    mockMvc
        .perform(
            get("/admin/personal-inventory")
                .param("userSub", userSub)
                .param("q", "Widget Alpha")
                .param("fragment", "results"))
        .andExpect(status().isOk());

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    ArgumentCaptor<Object> qCaptor = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef(), qCaptor.capture());
    assertTrue(uriCaptor.getValue().contains("q={q}"), uriCaptor.getValue());
    assertEquals("Widget Alpha", qCaptor.getValue());
  }

  // Same guard with an umlaut term: "Röhre Größe" encodes to R%C3%B6hre… under URLEncoder, which
  // the
  // hop would re-encode to a literal zero-match. As a URI variable the raw term reaches the
  // backend.
  @Test
  @WithMockUser(roles = "ADMIN")
  void view_passesUmlautQueryAsUriVariable_notFormEncoded() throws Exception {
    String userSub = UUID.randomUUID().toString();
    PageResponse<PersonalInventoryItemDto> items =
        new PageResponse<>(List.of(), 0, 50, 0, 0, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef(), any())).thenReturn(items);

    String term = "Röhre Größe";
    mockMvc
        .perform(
            get("/admin/personal-inventory")
                .param("userSub", userSub)
                .param("q", term)
                .param("fragment", "results"))
        .andExpect(status().isOk());

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    ArgumentCaptor<Object> qCaptor = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef(), qCaptor.capture());
    assertTrue(uriCaptor.getValue().contains("q={q}"), uriCaptor.getValue());
    assertEquals(term, qCaptor.getValue());
  }
}

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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.time.Instant;
import java.util.List;
import java.util.Set;
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
 * MVC-level test for {@link AdminPersonalBlueprintsPageController}: the admin Blueprints page
 * renders for an ADMIN and is forbidden for a non-admin (the {@code hasRole('ADMIN')} gate).
 */
@SpringBootTest
class AdminPersonalBlueprintsPageControllerMvcTest {

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
  @WithMockUser(roles = "ADMIN")
  void view_rendersForAdmin_withUserPicker() throws Exception {
    PageResponse<UserDto> users = new PageResponse<>(List.of(), 0, 1000, 0, 0, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(users);

    mockMvc
        .perform(get("/admin/personal-blueprints"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/personal-blueprints"))
        .andExpect(model().attributeExists("users"))
        .andExpect(model().attributeExists("blueprints"))
        .andExpect(model().attribute("adminMode", Boolean.TRUE));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void view_rendersIdPlaceholderToken_inPerRowEndpoints() throws Exception {
    PageResponse<UserDto> empty = new PageResponse<>(List.of(), 0, 1000, 0, 0, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(empty);

    // With a user selected, the inline-JS endpoint map renders. The per-row updateNote/remove
    // URLs must keep the literal ID_PLACEHOLDER token verbatim; a double-underscore __ID__ would
    // be eaten by Thymeleaf preprocessing and render "ID", 400ing on UUID parse.
    mockMvc
        .perform(
            get("/admin/personal-blueprints")
                .param("userSub", "00000000-0000-0000-0000-000000000009"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ID_PLACEHOLDER")));
  }

  // covers REQ-FE-002 — an AJAX filter swap (fragment=results) for a selected user renders only the
  // owned-blueprint table fragment: the row is present, but the swap-target wrapper, the admin
  // banner and the edit modal (all outside the fragment) are not.
  @Test
  @WithMockUser(roles = "ADMIN")
  void view_fragmentResults_rendersOnlyTableFragment() throws Exception {
    PersonalBlueprintDto bp =
        new PersonalBlueprintDto(
            UUID.randomUUID(),
            "arclight",
            "Arclight Pistol",
            UUID.randomUUID(),
            Instant.parse("2026-01-01T00:00:00Z"),
            "n",
            true,
            0L,
            null,
            null);
    // On the fragment path the controller skips the user-list fetch, so the only backend call is
    // fetchOwned — the single stub returns this blueprint page for it.
    PageResponse<PersonalBlueprintDto> page =
        new PageResponse<>(List.of(bp), 0, 200, 1L, 1, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    mockMvc
        .perform(
            get("/admin/personal-blueprints")
                .param("userSub", "00000000-0000-0000-0000-000000000009")
                .param("fragment", "results"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/personal-blueprints :: results"))
        .andExpect(content().string(containsString("Arclight Pistol")))
        .andExpect(content().string(not(containsString("id=\"bp-results\""))))
        .andExpect(content().string(not(containsString("id=\"krt-bp-edit-modal\""))))
        .andExpect(content().string(not(containsString("krt-admin-banner"))));
  }

  // covers REQ-FE-011 — the admin user picker is the shared searchable combobox, and each option
  // carries the username as a secondary search term (data-search) so typing the login name matches
  // a
  // user whose display name differs from it.
  @Test
  @WithMockUser(roles = "ADMIN")
  void view_userPicker_isSearchableComboboxWithUsernameSearchTerm() throws Exception {
    UserDto user =
        new UserDto(
            UUID.fromString("00000000-0000-0000-0000-000000000009"),
            "alice_login",
            "Alice Display",
            "Alice Display",
            null,
            null,
            null,
            Set.of(),
            Set.of(),
            null,
            Boolean.FALSE,
            Boolean.FALSE,
            Boolean.TRUE,
            null,
            null,
            0L,
            null,
            null);
    // With no userSub the only backend call is the user-list fetch (blueprints default to empty),
    // so
    // a single stub safely seeds the picker options without leaking into the blueprint table.
    PageResponse<UserDto> users = new PageResponse<>(List.of(user), 0, 1000, 1L, 1, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(users);

    mockMvc
        .perform(get("/admin/personal-blueprints"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-krt-combobox")))
        .andExpect(content().string(containsString("data-search=\"alice_login\"")))
        .andExpect(content().string(containsString("Alice Display")));
  }

  // covers REQ-INV-024 — the global "delete all users' blueprints" danger zone renders for an admin
  // independently of the member picker, carrying the type-to-confirm token input and the purge form
  // that targets the ADMIN purge endpoint.
  @Test
  @WithMockUser(roles = "ADMIN")
  void view_rendersGlobalPurgeDangerZone() throws Exception {
    PageResponse<UserDto> users = new PageResponse<>(List.of(), 0, 1000, 0, 0, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(users);

    mockMvc
        .perform(get("/admin/personal-blueprints"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"bp-purge-modal\"")))
        .andExpect(content().string(containsString("data-bp-purge")))
        .andExpect(content().string(containsString("data-confirm-token=\"LOESCHEN\"")))
        .andExpect(content().string(containsString("/admin/personal-blueprints/delete-all-users")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void view_forbiddenForNonAdmin() throws Exception {
    mockMvc.perform(get("/admin/personal-blueprints")).andExpect(status().isForbidden());
  }
}

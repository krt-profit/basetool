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

import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyClass;
import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
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
import org.mockito.ArgumentCaptor;
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
    mockMvc
        .perform(get("/admin/personal-blueprints"))
        .andExpect(status().isOk())
        .andExpect(view().name("admin/personal-blueprints"))
        .andExpect(content().string(containsString("data-krt-combobox=\"remote-users\"")))
        .andExpect(model().attributeExists("blueprints"))
        .andExpect(model().attribute("adminMode", Boolean.TRUE));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void view_withoutMember_rendersOnlyThePurgeDialog() throws Exception {
    String html =
        mockMvc
            .perform(get("/admin/personal-blueprints"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(html)
        .contains("id=\"bp-purge-modal\"")
        .doesNotContain("id=\"krt-bp-edit-modal\"")
        .doesNotContain("id=\"krt-bp-delete-modal\"")
        .doesNotContain("id=\"krt-bp-import-modal\"")
        .doesNotContain("personal-inventory-blueprints-import.js");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void view_withMember_rendersTheMembersDialogsTogetherWithTheImportScript() throws Exception {
    PageResponse<UserDto> empty = new PageResponse<>(List.of(), 0, 1000, 0, 0, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(empty);
    String html =
        mockMvc
            .perform(
                get("/admin/personal-blueprints")
                    .param("userSub", "00000000-0000-0000-0000-000000000009"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(html)
        .contains("id=\"krt-bp-edit-modal\"")
        .contains("id=\"krt-bp-delete-modal\"")
        .contains("id=\"krt-bp-import-modal\"")
        .contains("personal-inventory-blueprints-import.js");
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void view_rendersIdPlaceholderToken_inPerRowEndpoints() throws Exception {
    PageResponse<UserDto> empty = new PageResponse<>(List.of(), 0, 1000, 0, 0, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(empty);

    mockMvc
        .perform(
            get("/admin/personal-blueprints")
                .param("userSub", "00000000-0000-0000-0000-000000000009"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("ID_PLACEHOLDER")));
  }

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

  @Test
  @WithMockUser(roles = "ADMIN")
  void view_passesMultiWordQueryAsUriVariable() throws Exception {
    String userSub = UUID.randomUUID().toString();
    PageResponse<PersonalBlueprintDto> page =
        new PageResponse<>(List.of(), 0, 200, 0, 0, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef(), any())).thenReturn(page);

    mockMvc
        .perform(
            get("/admin/personal-blueprints")
                .param("userSub", userSub)
                .param("q", "Arclight Pistol")
                .param("fragment", "results"))
        .andExpect(status().isOk());

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    ArgumentCaptor<Object> qCaptor = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef(), qCaptor.capture());
    assertTrue(uriCaptor.getValue().contains("q={q}"), uriCaptor.getValue());
    assertEquals("Arclight Pistol", qCaptor.getValue());
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void view_passesUmlautQueryAsUriVariable_notFormEncoded() throws Exception {
    String userSub = UUID.randomUUID().toString();
    PageResponse<PersonalBlueprintDto> page =
        new PageResponse<>(List.of(), 0, 200, 0, 0, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef(), any())).thenReturn(page);

    String term = "Größe Röhre";
    mockMvc
        .perform(
            get("/admin/personal-blueprints")
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

  @Test
  @WithMockUser(roles = "ADMIN")
  void view_userPicker_isRemoteSearchCombobox_withoutRosterPreload() throws Exception {
    mockMvc
        .perform(get("/admin/personal-blueprints"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-krt-combobox=\"remote-users\"")))
        .andExpect(content().string(not(containsString("data-search"))));
  }

  @Test
  @WithMockUser(roles = "ADMIN")
  void view_userPicker_seedsSelectedMemberInEditMode() throws Exception {
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
    PageResponse<PersonalBlueprintDto> emptyBlueprints =
        new PageResponse<>(List.of(), 0, 200, 0, 0, List.of());
    when(backendApiClient.get(anyString(), anyClass())).thenReturn(user);
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(emptyBlueprints);

    mockMvc
        .perform(
            get("/admin/personal-blueprints")
                .param("userSub", "00000000-0000-0000-0000-000000000009"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-krt-combobox=\"remote-users\"")))
        .andExpect(content().string(containsString("Alice Display")))
        .andExpect(content().string(containsString("00000000-0000-0000-0000-000000000009")));
  }

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

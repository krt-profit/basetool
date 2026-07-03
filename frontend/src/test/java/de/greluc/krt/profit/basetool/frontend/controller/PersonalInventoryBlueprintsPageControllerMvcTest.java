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
 * MVC-level test for {@link PersonalInventoryBlueprintsPageController}: verifies the Blueprints
 * sub-page renders for an authenticated user with the owned-blueprint list filled from the (mocked)
 * backend.
 */
@SpringBootTest
class PersonalInventoryBlueprintsPageControllerMvcTest {

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
  @WithMockUser
  void view_shouldRenderBlueprintsView_whenAuthenticated() throws Exception {
    PersonalBlueprintDto bp =
        new PersonalBlueprintDto(
            UUID.randomUUID(),
            "arclight pistol",
            "Arclight Pistol",
            null,
            Instant.parse("2026-01-01T00:00:00Z"),
            "note",
            true,
            0L,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"));
    PageResponse<PersonalBlueprintDto> page =
        new PageResponse<>(List.of(bp), 0, 200, 1, 1, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    mockMvc
        .perform(get("/personal-inventory/blueprints"))
        .andExpect(status().isOk())
        .andExpect(view().name("personal-inventory-blueprints"))
        .andExpect(model().attributeExists("blueprints"))
        // Regression guard (#363): the per-row id placeholder must survive Thymeleaf rendering
        // verbatim. The previous `__ID__` token was eaten by Thymeleaf preprocessing (`__...__`),
        // rendering `/blueprints/ID/recipe` and 400-ing every expand/edit/delete. We assert the
        // bare token (not the full path) because JS inlining escapes the slashes to `\/`.
        .andExpect(content().string(containsString("ID_PLACEHOLDER")));
  }

  @Test
  @WithMockUser
  void view_loadsEveryPage_whenOwnedSetExceedsOnePage() throws Exception {
    // covers REQ-INV-008 / issue #823 — the list must show the caller's COMPLETE owned set, not a
    // capped first page. The controller pages through the backend until the last page, so a user
    // with more blueprints than one fetch chunk still sees (and can search/count) all of them.
    PersonalBlueprintDto first =
        new PersonalBlueprintDto(
            UUID.randomUUID(),
            "arclight pistol",
            "Arclight Pistol",
            null,
            Instant.parse("2026-01-01T00:00:00Z"),
            null,
            true,
            0L,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"));
    PersonalBlueprintDto second =
        new PersonalBlueprintDto(
            UUID.randomUUID(),
            "demeco lmg",
            "Demeco LMG",
            null,
            Instant.parse("2026-01-01T00:00:00Z"),
            null,
            true,
            0L,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"));
    // Two pages of one entry each (totalPages = 2): the controller must request both and
    // concatenate.
    PageResponse<PersonalBlueprintDto> page0 =
        new PageResponse<>(List.of(first), 0, 500, 2, 2, List.of());
    PageResponse<PersonalBlueprintDto> page1 =
        new PageResponse<>(List.of(second), 1, 500, 2, 2, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page0, page1);

    mockMvc
        .perform(get("/personal-inventory/blueprints"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("blueprints", org.hamcrest.Matchers.hasSize(2)))
        .andExpect(content().string(containsString("Arclight Pistol")))
        .andExpect(content().string(containsString("Demeco LMG")));
  }

  @Test
  @WithMockUser
  void view_rendersDeleteAllControl_whenOwnerHasRemovableBlueprints() throws Exception {
    // covers REQ-INV-023 — the "delete all my blueprints" button (modal trigger) and its danger
    // confirm modal render when the caller owns at least one removable blueprint.
    PersonalBlueprintDto bp =
        new PersonalBlueprintDto(
            UUID.randomUUID(),
            "arclight pistol",
            "Arclight Pistol",
            null,
            Instant.parse("2026-01-01T00:00:00Z"),
            null,
            true,
            0L,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"));
    PageResponse<PersonalBlueprintDto> page =
        new PageResponse<>(List.of(bp), 0, 200, 1, 1, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    mockMvc
        .perform(get("/personal-inventory/blueprints"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"krt-bp-delete-all-open\"")))
        .andExpect(content().string(containsString("data-trigger=\"bp-open-delete-all\"")))
        .andExpect(content().string(containsString("id=\"krt-bp-delete-all-modal\"")));
  }

  @Test
  @WithMockUser
  void view_fragmentList_rendersOnlyTheCollectionCardFragment() throws Exception {
    // The in-place swap target: GET /personal-inventory/blueprints?fragment=list returns just the
    // blueprintList fragment (the master/detail card) and NOT the surrounding page chrome (the add
    // bar, the import/edit modals), so a batch add / import / remove can re-render the list without
    // reloading (REQ-FE-005).
    PersonalBlueprintDto bp =
        new PersonalBlueprintDto(
            UUID.randomUUID(),
            "arclight pistol",
            "Arclight Pistol",
            null,
            Instant.parse("2026-01-01T00:00:00Z"),
            "note",
            true,
            0L,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"));
    PageResponse<PersonalBlueprintDto> page =
        new PageResponse<>(List.of(bp), 0, 200, 1, 1, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    mockMvc
        .perform(get("/personal-inventory/blueprints").param("fragment", "list"))
        .andExpect(status().isOk())
        .andExpect(view().name("personal-inventory-blueprints :: blueprintList"))
        .andExpect(content().string(containsString("id=\"krt-bp-master-rows\"")))
        .andExpect(content().string(containsString("id=\"krt-bp-total-meta\"")))
        // the import modal lives outside the fragment and must not be in the swap body
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(containsString("id=\"krt-bp-import-modal\""))));
  }
}

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.startsWith;
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
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
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
            Instant.parse("2026-01-01T00:00:00Z"),
            "LOG",
            "versekit",
            "VerseKit");
    PageResponse<PersonalBlueprintDto> page =
        new PageResponse<>(List.of(bp), 0, 200, 1, 1, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    mockMvc
        .perform(get("/personal-inventory/blueprints"))
        .andExpect(status().isOk())
        .andExpect(view().name("personal-inventory-blueprints"))
        .andExpect(model().attributeExists("blueprints"))
        .andExpect(content().string(containsString("ID_PLACEHOLDER")))
        .andExpect(content().string(containsString("data-source=\"LOG\"")))
        .andExpect(content().string(containsString("data-source-client=\"versekit\"")))
        .andExpect(content().string(containsString("data-source-client-name=\"VerseKit\"")));
  }

  @Test
  @WithMockUser
  void view_leavesTheClientNameOut_whenTheClientIsNoLongerRegistered() throws Exception {
    PersonalBlueprintDto bp =
        new PersonalBlueprintDto(
            UUID.randomUUID(),
            "arclight pistol",
            "Arclight Pistol",
            null,
            null,
            null,
            true,
            0L,
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:00Z"),
            "LOG",
            "retired-tool",
            null);
    PageResponse<PersonalBlueprintDto> page =
        new PageResponse<>(List.of(bp), 0, 200, 1, 1, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    mockMvc
        .perform(get("/personal-inventory/blueprints"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-source-client=\"retired-tool\"")))
        .andExpect(content().string(not(containsString("data-source-client-name"))));
  }

  @Test
  @WithMockUser
  void view_loadsEveryPage_whenOwnedSetExceedsOnePage() throws Exception {
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
            Instant.parse("2026-01-01T00:00:00Z"),
            null,
            null,
            null);
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
            Instant.parse("2026-01-01T00:00:00Z"),
            null,
            null,
            null);
    PageResponse<PersonalBlueprintDto> page0 =
        new PageResponse<>(List.of(first), 0, 500, 2, 2, List.of());
    PageResponse<PersonalBlueprintDto> page1 =
        new PageResponse<>(List.of(second), 1, 500, 2, 2, List.of());
    when(backendApiClient.get(startsWith("/api/v1/personal-blueprints?"), anyTypeRef()))
        .thenReturn(page0, page1);

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
            Instant.parse("2026-01-01T00:00:00Z"),
            null,
            null,
            null);
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
  void view_rendersScExtractorReleaseLink_besideTheJsonImportTrigger() throws Exception {
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
            Instant.parse("2026-01-01T00:00:00Z"),
            null,
            null,
            null);
    PageResponse<PersonalBlueprintDto> page =
        new PageResponse<>(List.of(bp), 0, 200, 1, 1, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    mockMvc
        .perform(get("/personal-inventory/blueprints"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"krt-bp-extractor-link\"")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "href=\"https://github.com/krt-profit/basetool-sc-extractor/releases/latest\"")))
        .andExpect(content().string(containsString("rel=\"noopener noreferrer\"")))
        .andExpect(
            result -> {
              String html = result.getResponse().getContentAsString();
              assertTrue(
                  html.indexOf("krt-bp-extractor-link") < html.indexOf("id=\"krt-bp-list\""),
                  "extractor link must render in the add bar, above the swapped collection list");
            });
  }

  /**
   * The page follows the hand-off draft: „Mein Inventar" with the „Persönlich" eyebrow, one primary
   * action that opens the add dialog, „Importieren" as a ghost button, the extractor link and „Alle
   * löschen" in the overflow menu, both tab counts, and exactly one search on the page — the
   * catalogue search lives in the dialog.
   */
  @Test
  @WithMockUser
  void view_rendersThePageHeadToolbarAndAddDialog() throws Exception {
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
            Instant.parse("2026-01-01T00:00:00Z"),
            null,
            null,
            null);
    PageResponse<PersonalBlueprintDto> owned =
        new PageResponse<>(List.of(bp), 0, 500, 1, 1, List.of());
    PageResponse<PersonalBlueprintDto> items =
        new PageResponse<>(List.of(), 0, 1, 118, 118, List.of());
    when(backendApiClient.get(startsWith("/api/v1/personal-blueprints?"), anyTypeRef()))
        .thenReturn(owned);
    when(backendApiClient.get(startsWith("/api/v1/personal-inventory?"), anyTypeRef()))
        .thenReturn(items);

    String html =
        mockMvc
            .perform(get("/personal-inventory/blueprints").locale(Locale.GERMAN))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String main = html.substring(html.indexOf("<main"), html.indexOf("</main>"));
    String actions = main.substring(main.indexOf("page-actions"), main.indexOf("tab-nav"));
    assertTrue(main.contains("data-testid=\"page-eyebrow\""), main);
    assertTrue(main.contains("Mein Inventar"), main);
    assertEquals(1, actions.split("btn--cta", -1).length - 1, "one primary action");
    assertTrue(actions.contains("data-modal-id=\"krt-bp-add-modal\""), actions);
    assertTrue(actions.contains("id=\"krt-bp-import-open\" class=\"btn btn-ghost\""), actions);
    assertTrue(actions.contains("data-testid=\"overflow-menu-toggle\""), actions);
    assertTrue(actions.contains("id=\"krt-bp-extractor-link\""), actions);
    assertTrue(actions.contains("id=\"krt-bp-delete-all-open\""), actions);
    assertTrue(main.contains("<span class=\"tab-count\">118</span>"), main);
    assertTrue(main.contains("<span class=\"tab-count\">1</span>"), main);
    assertEquals(1, main.split("data-testid=\"toolbar-search\"", -1).length - 1, "one search");
    assertFalse(main.contains("id=\"krt-bp-search-input\""), "catalogue search is in the dialog");
    assertTrue(html.contains("id=\"krt-bp-add-modal\""), html);
    assertTrue(html.contains("id=\"krt-bp-search-input\""), html);
    assertTrue(html.contains("id=\"krt-bp-staging-list\""), html);
    assertTrue(html.contains("id=\"krt-bp-add-selected\""), html);
    assertFalse(main.contains("hud-box"), "no HUD box");
    assertFalse(main.contains("class=\"greeting"), "no greeting");
    assertFalse(main.contains("krtm-"), "no migrated inline classes");
    assertTrue(main.contains("class=\"krt-bp-dot\""), main);
  }

  @Test
  @WithMockUser
  void view_rendersCraftableSegment_andRefinerySwitch() throws Exception {
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
            Instant.parse("2026-01-01T00:00:00Z"),
            null,
            null,
            null);
    PageResponse<PersonalBlueprintDto> page =
        new PageResponse<>(List.of(bp), 0, 200, 1, 1, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    mockMvc
        .perform(get("/personal-inventory/blueprints"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"krt-bp-refinery-toggle\"")))
        .andExpect(
            content().string(containsString("class=\"switch\" for=\"krt-bp-refinery-toggle\"")))
        .andExpect(content().string(containsString("data-testid=\"segment-bpScope-all\"")))
        .andExpect(content().string(containsString("data-testid=\"segment-bpScope-craftable\"")))
        .andExpect(content().string(not(containsString("krt-bp-craftable-toggle"))));
  }

  /**
   * The recipe script receives the shortfall label for a short ingredient (REQ-INV-048).
   *
   * @throws Exception if the request fails
   */
  @Test
  @WithMockUser
  void view_passesTheShortfallLabelToTheRecipeScript() throws Exception {
    PageResponse<PersonalBlueprintDto> page =
        new PageResponse<>(List.of(), 0, 200, 0, 0, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    mockMvc
        .perform(get("/personal-inventory/blueprints").locale(Locale.GERMAN))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("missing:          \"fehlt {0}\"")));
  }

  @Test
  @WithMockUser
  void view_fragmentList_rendersOnlyTheCollectionCardFragment() throws Exception {
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
            Instant.parse("2026-01-01T00:00:00Z"),
            null,
            null,
            null);
    PageResponse<PersonalBlueprintDto> page =
        new PageResponse<>(List.of(bp), 0, 200, 1, 1, List.of());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    mockMvc
        .perform(get("/personal-inventory/blueprints").param("fragment", "list"))
        .andExpect(status().isOk())
        .andExpect(view().name("personal-inventory-blueprints :: blueprintList"))
        .andExpect(content().string(containsString("id=\"krt-bp-master-rows\"")))
        .andExpect(content().string(containsString("id=\"krt-bp-total-meta\"")))
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(containsString("id=\"krt-bp-import-modal\""))));
  }

  @Test
  @WithMockUser
  void search_passesMultiWordQueryAsUriVariable() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any())).thenReturn(List.of());

    mockMvc
        .perform(get("/personal-inventory/blueprints/search").param("q", "Arclight Pistol"))
        .andExpect(status().isOk());

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    ArgumentCaptor<Object> qCaptor = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef(), qCaptor.capture());
    assertTrue(uriCaptor.getValue().contains("q={q}"), uriCaptor.getValue());
    assertEquals("Arclight Pistol", qCaptor.getValue());
  }

  @Test
  @WithMockUser
  void search_passesUmlautQueryAsUriVariable_notFormEncoded() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any())).thenReturn(List.of());

    String term = "Röhre Größe";
    mockMvc
        .perform(get("/personal-inventory/blueprints/search").param("q", term))
        .andExpect(status().isOk());

    ArgumentCaptor<String> uriCaptor = ArgumentCaptor.captor();
    ArgumentCaptor<Object> qCaptor = ArgumentCaptor.captor();
    verify(backendApiClient).get(uriCaptor.capture(), anyTypeRef(), qCaptor.capture());
    assertTrue(uriCaptor.getValue().contains("q={q}"), uriCaptor.getValue());
    assertEquals(term, qCaptor.getValue());
  }
}

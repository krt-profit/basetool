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
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.stringContainsInOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import de.greluc.krt.profit.basetool.frontend.model.dto.AggregatedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.GroupedInventoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.InventoryStackDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderAllocationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderGameItemNeedDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderMaterialNeedDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.JobOrderReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.LocationReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionAllocationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.support.PageStylesheets;
import de.greluc.krt.profit.basetool.frontend.support.PickerSearch;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@ActiveProfiles("test")
class InventoryPageControllerMvcTest {

  /**
   * The middle dot an allocation-picker option puts between the order id and its outstanding-need
   * suffix (REQ-INV-039). Written as an escape rather than typed inline so a source-encoding
   * accident cannot silently weaken the assertions that pin the label.
   */
  private static final String NEED_SEPARATOR = "\u00b7";

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

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewAggregatedInventory_AsMember_ShouldShowPage() throws Exception {
    PageResponse<AggregatedInventoryDto> page =
        new PageResponse<>(List.of(), 0, 10, 0, 1, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-index"))
        .andExpect(model().attributeExists("aggregated"))
        .andExpect(content().string(containsString("colspan=\"4\"")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewAllInventory_AsMember_ShouldShowPage() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/all"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-admin"))
        .andExpect(model().attributeExists("groupedItems"));
  }

  @Test
  @WithMockUser(roles = "LOGISTICIAN")
  void viewAllInventory_AsLogistician_ShouldShowActions() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/all"))
        .andDo(org.springframework.test.web.servlet.result.MockMvcResultHandlers.print())
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Einbuchen")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewAllInventory_AsMember_ShouldNotShowActions() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/all"))
        .andExpect(status().isOk())
        .andExpect(content().string(not(containsString("Einbuchen"))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewAllInventory_ShouldRenderBookOutAndUmbuchenControls() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/all"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"bookOutSubmitBtn\"")))
        .andExpect(content().string(containsString("data-text-discard=\"Ausbuchen\"")))
        .andExpect(content().string(containsString("data-text-sell=\"Verkaufen\"")))
        .andExpect(content().string(not(containsString("data-text-transfer"))))
        .andExpect(content().string(containsString("id=\"umbuchenModal\"")))
        .andExpect(content().string(containsString("id=\"umbuchenSubmitBtn\"")))
        .andExpect(content().string(containsString("data-herkunft=\"bookout\"")))
        .andExpect(content().string(containsString("data-herkunft=\"umbuchen\"")))
        .andExpect(content().string(containsString("/js/inventory-herkunft.js")))
        .andExpect(content().string(containsString("/js/inventory-common.js")))
        .andExpect(content().string(containsString("terminalLoading")))
        .andExpect(content().string(containsString("terminalNoMaterial")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMyInventory_ShouldRenderHerkunftPicker() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/my"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-herkunft=\"bookout\"")))
        .andExpect(content().string(containsString("data-herkunft=\"umbuchen\"")))
        .andExpect(content().string(containsString("data-herkunft-body")))
        .andExpect(content().string(containsString("/js/inventory-herkunft.js")))
        .andExpect(content().string(containsString("/js/inventory-common.js")))
        .andExpect(content().string(containsString("terminalLoading")));
  }

  @ParameterizedTest
  @ValueSource(strings = {"/inventory/my", "/inventory/all"})
  @WithMockUser(roles = "KRT_MEMBER")
  void inventoryPages_ShouldBootstrapHerkunftAutoPrefillLabel(String path) throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get(path))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("auto: \"Automatisch vorbef")));
  }

  /**
   * The "Mein Lager" filter row sits in a collapsible panel in both views (REQ-INV-037): the shared
   * toggle carries {@code aria-expanded}/{@code aria-controls}, the panel is rendered expanded, the
   * filter form is inside it, and the count chip keeps the raw {@code {0}} placeholder.
   *
   * @param path the Lager view under test, each rendering a different filter form
   * @throws Exception if the request fails
   */
  @ParameterizedTest
  @ValueSource(strings = {"/inventory/my", "/inventory/my?view=items"})
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMyInventory_rendersTheFilterRowInsideACollapsiblePanel(String path) throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get(path))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("aria-controls=\"myFilterPanel\"")))
        .andExpect(content().string(containsString("aria-expanded=\"true\"")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "<div class=\"filter-panel\" id=\"myFilterPanel\""
                            + " data-filter-panel=\"inventory-my\">")))
        .andExpect(content().string(containsString("data-label=\"Aktive Filter: {0}\"")))
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        List.of(
                            "data-testid=\"lager-filter-toggle\"",
                            "id=\"myFilterPanel\"",
                            "my-inventory-filter",
                            "id=\"bulkCheckoutBar\""))));
  }

  /**
   * The "Globales Lager" filter row sits in a collapsible panel in both views (REQ-INV-037), with
   * the same four guarantees as the "Mein Lager" test; the source-order assertion ensures the panel
   * wraps the filter forms themselves.
   *
   * @param path the Lager view under test, each rendering a different filter form
   * @throws Exception if the request fails
   */
  @ParameterizedTest
  @ValueSource(strings = {"/inventory/all", "/inventory/all?view=items"})
  @WithMockUser(roles = "KRT_MEMBER")
  void viewAllInventory_rendersTheFilterRowInsideACollapsiblePanel(String path) throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get(path))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("aria-controls=\"globalFilterPanel\"")))
        .andExpect(content().string(containsString("aria-expanded=\"true\"")))
        .andExpect(
            content()
                .string(
                    containsString(
                        "<div class=\"filter-panel\" id=\"globalFilterPanel\""
                            + " data-filter-panel=\"inventory-all\">")))
        .andExpect(content().string(containsString("data-label=\"Aktive Filter: {0}\"")))
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        List.of(
                            "data-testid=\"lager-filter-toggle\"",
                            "id=\"globalFilterPanel\"",
                            "global-inventory-filter",
                            "id=\"tableContainer\""))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMyInventory_rendersSelectAllButtonBeforeBulkCheckout() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/my"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"bulkSelectAllBtn\"")))
        .andExpect(content().string(containsString("data-trigger=\"inv-my-select-all\"")))
        .andExpect(content().string(containsString("data-text-select")))
        .andExpect(content().string(containsString("data-text-clear")))
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        List.of("id=\"bulkSelectAllBtn\"", "id=\"bulkCheckoutBtn\""))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMyInventory_rendersBulkRebookButtonAndModalWithAllThreeModes() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/my"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"bulkRebookBtn\"")))
        .andExpect(content().string(containsString("data-trigger=\"inv-my-open-bulk-rebook\"")))
        .andExpect(content().string(containsString("id=\"bulkRebookModal\"")))
        .andExpect(content().string(containsString("id=\"bulkRebookForm\"")))
        .andExpect(content().string(containsString("value=\"PERSONALIZE\"")))
        .andExpect(content().string(containsString("value=\"DEPERSONALIZE\"")))
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        List.of("id=\"bulkCheckoutBtn\"", "id=\"bulkRebookBtn\""))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMyInventory_rendersTheOrgUnitChangeButtonAndDialog() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/my"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"bulkOrgUnitBtn\"")))
        .andExpect(content().string(containsString("data-trigger=\"inv-my-open-bulk-org-unit\"")))
        .andExpect(content().string(containsString("id=\"orgUnitChangeModal\"")))
        .andExpect(content().string(containsString("id=\"orgUnitChangeForm\"")))
        .andExpect(content().string(containsString("id=\"orgUnitChangeTarget\"")))
        .andExpect(content().string(containsString("var orgUnitChangeI18n")))
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        List.of("id=\"bulkRebookBtn\"", "id=\"bulkOrgUnitBtn\""))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMyInventory_umbuchenLocationPickerCarriesComboboxMarker() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/my"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(
                        "id=\"umbuchenTargetLocationId\" class=\"w-full\""
                            + " data-krt-combobox=\"remote-locations\"></select>")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewAllInventory_umbuchenLocationPickerCarriesComboboxMarker() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/all"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(
                        "id=\"umbuchenTargetLocationId\" class=\"w-full\""
                            + " data-krt-combobox=\"remote-locations\"></select>")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMyInventory_umbuchenMergeRowUsesSharedCheckRowLayout() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/my"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(
                        "id=\"umbuchenMergeRow\" class=\"form-group check-row mb-1 krtm-hidden\"")))
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        "id=\"umbuchenMergeRow\"",
                        "id=\"umbuchenMergeStock\"",
                        "for=\"umbuchenMergeStock\"",
                        "class=\"form-hint\"")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewAllInventory_umbuchenMergeRowUsesSharedCheckRowLayout() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/all"))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    containsString(
                        "id=\"umbuchenMergeRow\" class=\"form-group check-row mb-1 krtm-hidden\"")))
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        "id=\"umbuchenMergeRow\"",
                        "id=\"umbuchenMergeStock\"",
                        "for=\"umbuchenMergeStock\"",
                        "class=\"form-hint\"")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_personalAndMergeRowsUseSharedCheckRowLayout() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/input"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-input"))
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        "class=\"form-group check-row\"",
                        "id=\"personal\"",
                        "for=\"personal\"",
                        "class=\"form-hint\"",
                        "class=\"form-group check-row krtm-hidden\" id=\"merge-stock-row\"",
                        "id=\"mergeStock\"",
                        "for=\"mergeStock\"",
                        "class=\"form-hint\"")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_materialAndLocationPickersCarryComboboxMarker() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(eq(CachedCatalog.MATERIALS_LOOKUP), anyTypeRef()))
        .thenReturn(
            List.of(
                new MaterialReferenceDto(UUID.randomUUID(), "Quantanium", "SCU"),
                new MaterialReferenceDto(UUID.randomUUID(), "Laranite", "SCU")));
    when(backendApiClient.getCached(eq(CachedCatalog.LOCATIONS_LOOKUP), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/input"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-input"))
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        "id=\"materialId\"",
                        "data-krt-combobox=\"remote-materials\"",
                        "id=\"locationId\"",
                        "data-krt-combobox=\"remote-locations\"")))
        .andExpect(content().string(not(containsString("Quantanium"))))
        .andExpect(content().string(not(containsString("Laranite"))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_rendersCatalogModeToggleAndItemPickerWithoutQuality() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/input"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-input"))
        .andExpect(content().string(containsString("name=\"inventoryCatalogMode\"")))
        .andExpect(content().string(containsString("data-testid=\"inventory-mode-material\"")))
        .andExpect(content().string(containsString("data-testid=\"inventory-mode-item\"")))
        .andExpect(content().string(containsString("data-krt-combobox=\"remote-game-items\"")))
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        "id=\"mode-material-fields\"",
                        "id=\"quality\"",
                        "id=\"mode-item-fields\"",
                        "id=\"gameItemId\"")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_checkboxRowsShareCheckRowFormat() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/input"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-input"))
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        "class=\"form-group check-row\"",
                        "id=\"personal\"",
                        "class=\"form-hint\"",
                        "class=\"form-group check-row krtm-hidden\" id=\"merge-stock-row\"",
                        "id=\"mergeStock\"",
                        "class=\"form-hint\"")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_pageCssExcludesTogglesAndKeepsMergeRowHideable() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/input"))
        .andExpect(status().isOk())
        .andExpect(
            PageStylesheets.content(
                containsString(
                    ".form-group input:where(:not([type='checkbox']):not([type='radio']))")))
        .andExpect(
            PageStylesheets.content(not(containsString(".form-group.check-row.krtm-hidden"))));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_comboboxKindsMapCoversEveryRemoteSource() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/input").cookie(new Cookie("KRT_LOCALE", "de")))
        .andExpect(status().isOk())
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        "kinds: {",
                        "'remote-users':",
                        "'remote-bank-users':",
                        "'remote-materials':",
                        "'remote-materials-joborder':",
                        "'remote-materials-raw':",
                        "'remote-locations':",
                        "'remote-game-items':",
                        "'remote-bank-accounts':")))
        .andExpect(content().string(containsString("Material suchen oder w")))
        .andExpect(content().string(containsString("Ort suchen oder w")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void itemSearch_unwrapsBackendPageToFlatList() throws Exception {
    UUID itemId = UUID.randomUUID();
    PageResponse<InventoryGameItemReferenceDto> page =
        new PageResponse<>(
            List.of(new InventoryGameItemReferenceDto(itemId, "Quantum Drive", "RSI", "SHIP_ITEM")),
            0,
            50,
            1,
            1,
            Collections.emptyList());
    when(backendApiClient.get(
            eq(
                "/api/v1/inventory/item-catalog?size="
                    + PickerSearch.PAGE_SIZE
                    + "&sort=name,asc&q={q}"),
            anyTypeRef(),
            eq("Quantum")))
        .thenReturn(page);

    mockMvc
        .perform(get("/inventory/item-search").param("q", "Quantum"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(itemId.toString()))
        .andExpect(jsonPath("$[0].name").value("Quantum Drive"));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void itemSearch_passesMultiWordQueryAsUriVariable() throws Exception {
    UUID itemId = UUID.randomUUID();
    PageResponse<InventoryGameItemReferenceDto> page =
        new PageResponse<>(
            List.of(new InventoryGameItemReferenceDto(itemId, "Quantum Drive", "RSI", "SHIP_ITEM")),
            0,
            50,
            1,
            1,
            Collections.emptyList());
    when(backendApiClient.get(
            eq(
                "/api/v1/inventory/item-catalog?size="
                    + PickerSearch.PAGE_SIZE
                    + "&sort=name,asc&q={q}"),
            anyTypeRef(),
            eq("Quantum Drive")))
        .thenReturn(page);

    mockMvc
        .perform(get("/inventory/item-search").param("q", "Quantum Drive"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].name").value("Quantum Drive"));

    verify(backendApiClient)
        .get(
            eq(
                "/api/v1/inventory/item-catalog?size="
                    + PickerSearch.PAGE_SIZE
                    + "&sort=name,asc&q={q}"),
            anyTypeRef(),
            eq("Quantum Drive"));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void itemSearch_backendFailure_returnsEmptyList() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef(), any()))
        .thenThrow(new RuntimeException("backend down"));

    mockMvc
        .perform(get("/inventory/item-search").param("q", "Quantum"))
        .andExpect(status().isOk())
        .andExpect(content().json("[]"));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMaterialInventory_navigateSelectCarriesComboboxMarker() throws Exception {
    UUID selectedMaterialId = UUID.randomUUID();
    PageResponse<InventoryItemDto> page =
        new PageResponse<>(List.of(), 0, 10, 0, 1, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);
    when(backendApiClient.getCached(eq(CachedCatalog.MATERIALS_LOOKUP), anyTypeRef()))
        .thenReturn(
            List.of(
                new MaterialReferenceDto(selectedMaterialId, "Quantanium", "SCU"),
                new MaterialReferenceDto(UUID.randomUUID(), "Laranite", "SCU")));

    mockMvc
        .perform(get("/inventory/material/" + selectedMaterialId))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-material"))
        .andExpect(
            content()
                .string(
                    containsString(
                        "data-url-template=\"/inventory/material/{value}\""
                            + " data-krt-combobox=\"remote-materials\"")))
        .andExpect(content().string(containsString("Quantanium")))
        .andExpect(content().string(not(containsString("Laranite"))));
  }

  /**
   * The per-material drilldown renders a data row, the pager and the whitelisted size options for a
   * multi-page backend response (REQ-INV-033, ADR-0104).
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMaterialInventory_ShouldRenderPaginationControls() throws Exception {
    UUID materialId = UUID.randomUUID();
    InventoryItemDto item =
        new InventoryItemDto(
            UUID.randomUUID(),
            new UserReferenceDto(UUID.randomUUID(), "user", "User", "User", 1),
            new MaterialReferenceDto(materialId, "Quantanium", "SCU"),
            null,
            new LocationReferenceDto(UUID.randomUUID(), "Port Olisar"),
            80,
            10.0,
            false,
            List.of(),
            10.0,
            List.of(),
            10.0,
            null,
            null,
            1L,
            null,
            Instant.now());
    PageResponse<InventoryItemDto> page =
        new PageResponse<>(List.of(item), 1, 50, 120, 3, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(List.of(new MaterialReferenceDto(materialId, "Quantanium", "SCU")));

    mockMvc
        .perform(get("/inventory/material/" + materialId).param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-material"))
        .andExpect(content().string(containsString("Port Olisar")))
        .andExpect(content().string(containsString("class=\"pagination\"")))
        .andExpect(content().string(containsString("page=2")))
        .andExpect(content().string(containsString("size=50")))
        .andExpect(content().string(containsString(">200<")));
  }

  /**
   * Fragment-swap guard for the material drilldown pager (REQ-INV-033 / REQ-FE-005): a {@code
   * fragment=results} request renders only the results table + pager — no full page, no material
   * switcher — and must not re-fetch the cached material catalog (the REQ-DATA-012 fragment-gating
   * rule, so in-place paging does not amplify catalog reads).
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMaterialInventory_FragmentSwap_RendersPagerWithoutCatalogFetch() throws Exception {
    UUID materialId = UUID.randomUUID();
    PageResponse<InventoryItemDto> page =
        new PageResponse<>(List.of(), 1, 50, 120, 3, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);

    mockMvc
        .perform(
            get("/inventory/material/" + materialId)
                .param("page", "1")
                .param("fragment", "results"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("page=2")))
        .andExpect(content().string(not(containsString("materialSelect"))));

    verify(backendApiClient, never()).getCached(eq(CachedCatalog.MATERIALS_LOOKUP), anyTypeRef());
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewAllInventory_ShouldRenderScuDecimalAmountFieldsAndHelper() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/all"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-scu-decimal")))
        .andExpect(content().string(containsString("inputmode=\"decimal\"")))
        .andExpect(content().string(containsString("data-scu-allow-zero")))
        .andExpect(content().string(containsString("/js/scu-decimal-input.js")))
        .andExpect(content().string(containsString("window.krtScuInput")))
        .andExpect(content().string(containsString("window.krtScuI18n")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void viewAllInventory_ShouldRenderLocalStorageAttributes() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/all"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"inventoryTable\"")))
        .andExpect(content().string(containsString("data-user-id=\"test-user-123\"")));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void viewMyInventory_ShouldRenderLocalStorageAttributes() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/my"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("id=\"inventoryTable\"")))
        .andExpect(content().string(containsString("data-user-id=\"test-user-123\"")))
        .andExpect(content().string(containsString("id=\"personalOnly\"")));
  }

  /**
   * The personal stack-entries fragment renders the entry row and its mission allocation chip, even
   * when the mission is no longer returned by the active-mission lookup (REQ-INV-027).
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void viewMyStackEntries_ShouldRenderEntryRowsWithMissionFallbackOption() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();
    String missionName = "Op Sundown (archived)";

    InventoryItemDto item =
        new InventoryItemDto(
            itemId,
            new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
            new MaterialReferenceDto(materialId, "Quantanium", "SCU"),
            null,
            new LocationReferenceDto(locationId, "ARC-L1"),
            90,
            10.0,
            false,
            java.util.List.of(),
            null,
            java.util.List.of(new MissionAllocationDto(missionId, missionName, null, 4.0)),
            6.0,
            null,
            null,
            1L,
            null,
            Instant.parse("2026-02-03T10:15:30Z"));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/my-inventory/stack/entries")) {
                return new PageResponse<>(List.of(item), 0, 20, 1, 1, Collections.emptyList());
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(
            get("/inventory/my/stack/entries")
                .param("materialId", materialId.toString())
                .param("locationId", locationId.toString())
                .param("quality", "90")
                .param("missionId", missionId.toString())
                .param("personal", "false"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-item-id=\"" + itemId + "\"")))
        .andExpect(content().string(containsString("assoc-chip--mission")))
        .andExpect(content().string(containsString("data-target-id=\"" + missionId + "\"")))
        .andExpect(content().string(containsString(missionName)));
  }

  /**
   * A {@code PIECE} material's allocation and rest chips render whole numbers ({@code 5}, {@code
   * 10}) rather than three decimals (REQ-INV-027).
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void viewMyStackEntries_ShouldRenderPieceAmountsWhole() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();

    InventoryItemDto item =
        new InventoryItemDto(
            itemId,
            new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
            new MaterialReferenceDto(materialId, "Titanium Bolt", "PIECE"),
            null,
            new LocationReferenceDto(locationId, "ARC-L1"),
            90,
            10.0,
            false,
            java.util.List.of(new JobOrderAllocationDto(orderId, 42, 5.0)),
            5.0,
            java.util.List.of(),
            10.0,
            null,
            null,
            1L,
            null,
            Instant.parse("2026-02-03T10:15:30Z"));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/my-inventory/stack/entries")) {
                return new PageResponse<>(List.of(item), 0, 20, 1, 1, Collections.emptyList());
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(
            get("/inventory/my/stack/entries")
                .param("materialId", materialId.toString())
                .param("locationId", locationId.toString())
                .param("quality", "90")
                .param("personal", "false"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-item-id=\"" + itemId + "\"")))
        .andExpect(content().string(containsString("assoc-chip--order")))
        .andExpect(content().string(not(containsString("5.000"))))
        .andExpect(content().string(not(containsString("10.000"))));
  }

  /**
   * The stack-entry "Auftrag" picker offers only orders whose {@code requiredMaterialIds} include
   * the entry's material, including ITEM orders (REQ-ORDERS-018).
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void viewMyStackEntries_ShouldOfferOnlyOrdersThatRequireTheEntryMaterial() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID matchingOrderId = UUID.randomUUID();
    UUID unrelatedOrderId = UUID.randomUUID();

    InventoryItemDto item =
        new InventoryItemDto(
            itemId,
            new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
            new MaterialReferenceDto(materialId, "Quantanium", "SCU"),
            null,
            new LocationReferenceDto(locationId, "ARC-L1"),
            90,
            10.0,
            false,
            java.util.List.of(),
            null,
            java.util.List.of(),
            null,
            null,
            null,
            1L,
            null,
            Instant.parse("2026-02-03T10:15:30Z"));

    JobOrderReferenceDto matching =
        new JobOrderReferenceDto(
            matchingOrderId,
            71,
            "h1",
            "IN_PROGRESS",
            null,
            List.of(),
            List.of(materialId),
            List.of(),
            List.of(),
            List.of());
    JobOrderReferenceDto unrelated =
        new JobOrderReferenceDto(
            unrelatedOrderId,
            99,
            "h2",
            "IN_PROGRESS",
            null,
            List.of(),
            List.of(UUID.randomUUID()),
            List.of(),
            List.of(),
            List.of());

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/my-inventory/stack/entries")) {
                return new PageResponse<>(List.of(item), 0, 20, 1, 1, Collections.emptyList());
              }
              if (url.contains("/orders/lookup")) {
                return List.of(matching, unrelated);
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(
            get("/inventory/my/stack/entries")
                .param("materialId", materialId.toString())
                .param("locationId", locationId.toString())
                .param("quality", "90")
                .param("personal", "false"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("value=\"" + matchingOrderId + "\"")))
        .andExpect(content().string(not(containsString("value=\"" + unrelatedOrderId + "\""))));
  }

  /**
   * The book-in form's order options expose the order's {@code requiredMaterialIds} in {@code
   * data-materials}, so ITEM orders are filtered correctly (REQ-ORDERS-018).
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_ShouldKeyOrderDataMaterialsOnRequiredMaterialIds() throws Exception {
    UUID materialId = UUID.randomUUID();
    UUID itemOrderId = UUID.randomUUID();

    JobOrderReferenceDto itemOrder =
        new JobOrderReferenceDto(
            itemOrderId,
            71,
            "craft-1",
            "IN_PROGRESS",
            null,
            List.of(),
            List.of(materialId),
            List.of(),
            List.of(),
            List.of());

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/orders/lookup")) {
                return List.of(itemOrder);
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/input"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-input"))
        .andExpect(
            content()
                .string(
                    stringContainsInOrder(
                        "value=\"" + itemOrderId + "\"", "data-materials=\"" + materialId + "\"")))
        .andExpect(content().string(not(containsString("data-materials=\"\""))));
  }

  /**
   * The {@code /inventory/input} form embeds each order's outstanding per-material need as one JSON
   * blob, requested from the lookup with {@code withNeeds=true} (REQ-INV-039).
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_ShouldEmbedTheOutstandingNeedFigures() throws Exception {
    UUID materialId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();

    JobOrderReferenceDto order =
        new JobOrderReferenceDto(
            orderId,
            71,
            "h1",
            "IN_PROGRESS",
            null,
            List.of(),
            List.of(materialId),
            List.of(),
            List.of(new JobOrderMaterialNeedDto(materialId, 650, 400.0, 150.0, 250.0)),
            List.of());

    java.util.List<String> lookupUrls = new java.util.ArrayList<>();
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/orders/lookup")) {
                lookupUrls.add(url);
                return List.of(order);
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/input"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-input"))
        .andExpect(content().string(containsString("data-order-needs=")))
        .andExpect(content().string(containsString(orderId.toString())))
        .andExpect(content().string(containsString("&quot;outstandingAmount&quot;:250.0")))
        .andExpect(content().string(containsString("&quot;qualityFloor&quot;:650")))
        .andExpect(content().string(containsString("&quot;materials&quot;")))
        .andExpect(content().string(containsString("&quot;gameItems&quot;")));

    assertThat(lookupUrls).isNotEmpty().allMatch(url -> url.contains("withNeeds=true"));
  }

  /**
   * The AJAX-only {@code /inventory/order-needs} relay returns the same shape the page embeds,
   * keyed by order id (REQ-FE-010).
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void orderNeedsAjax_ShouldAnswerTheNeedsKeyedByOrderId() throws Exception {
    UUID materialId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID needlessOrderId = UUID.randomUUID();

    JobOrderReferenceDto order =
        new JobOrderReferenceDto(
            orderId,
            71,
            "h1",
            "IN_PROGRESS",
            null,
            List.of(),
            List.of(materialId),
            List.of(),
            List.of(new JobOrderMaterialNeedDto(materialId, null, 400.0, 150.0, 250.0)),
            List.of());
    JobOrderReferenceDto needless =
        new JobOrderReferenceDto(
            needlessOrderId,
            72,
            "h2",
            "OPEN",
            null,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              return url.contains("/orders/lookup")
                  ? List.of(order, needless)
                  : Collections.emptyList();
            });

    mockMvc
        .perform(get("/inventory/order-needs").header("X-Requested-With", "XMLHttpRequest"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.materials['" + orderId + "'][0].outstandingAmount").value(250.0))
        .andExpect(
            jsonPath("$.materials['" + orderId + "'][0].materialId").value(materialId.toString()))
        .andExpect(jsonPath("$.materials['" + needlessOrderId + "']").doesNotExist())
        .andExpect(jsonPath("$.gameItems").exists());
  }

  /**
   * The per-entry "+ Zuordnen" popover labels each order option, server-side, with that order's
   * outstanding need of the entry's material (REQ-INV-039).
   */
  @Test
  @WithMockUser(roles = "LOGISTICIAN", username = "logi-user")
  void viewAllStackEntries_ShouldLabelOrderOptionsWithTheOutstandingNeed() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();

    InventoryItemDto item =
        new InventoryItemDto(
            itemId,
            new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
            new MaterialReferenceDto(materialId, "Tungsten", "SCU"),
            null,
            new LocationReferenceDto(locationId, "ARC-L1"),
            90,
            10.0,
            false,
            java.util.List.of(),
            null,
            java.util.List.of(),
            10.0,
            null,
            null,
            1L,
            null,
            Instant.parse("2026-01-01T00:00:00Z"));

    JobOrderReferenceDto order =
        new JobOrderReferenceDto(
            orderId,
            1042,
            "h1",
            "IN_PROGRESS",
            null,
            List.of(),
            List.of(materialId),
            List.of(),
            List.of(new JobOrderMaterialNeedDto(materialId, null, 400.0, 150.0, 250.0)),
            List.of());

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/all/stack/entries")) {
                return new PageResponse<>(List.of(item), 0, 20, 1, 1, Collections.emptyList());
              }
              if (url.contains("/orders/lookup")) {
                return List.of(order);
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(
            get("/inventory/all/stack/entries")
                .param("materialId", materialId.toString())
                .param("userId", userId.toString())
                .param("locationId", locationId.toString())
                .param("quality", "90"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("#1042 " + NEED_SEPARATOR + " noch 250")))
        .andExpect(content().string(containsString("value=\"" + orderId + "\"")));
  }

  /**
   * Same as {@link #viewMyStackEntries_ShouldRenderEntryRowsWithMissionFallbackOption()} for the
   * logistician stack-entries fragment ({@code /inventory/all/stack/entries}): an archived mission
   * still shows as an allocation chip.
   */
  @Test
  @WithMockUser(roles = "LOGISTICIAN", username = "logi-user")
  void viewAllStackEntries_ShouldRenderEntryRowsWithMissionFallbackOption() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID missionId = UUID.randomUUID();
    String missionName = "Op Sundown (archived)";

    InventoryItemDto item =
        new InventoryItemDto(
            itemId,
            new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
            new MaterialReferenceDto(materialId, "Quantanium", "SCU"),
            null,
            new LocationReferenceDto(locationId, "ARC-L1"),
            90,
            10.0,
            false,
            java.util.List.of(),
            null,
            java.util.List.of(new MissionAllocationDto(missionId, missionName, null, 4.0)),
            6.0,
            null,
            null,
            1L,
            null,
            Instant.parse("2026-01-01T00:00:00Z"));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/all/stack/entries")) {
                return new PageResponse<>(List.of(item), 0, 20, 1, 1, Collections.emptyList());
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(
            get("/inventory/all/stack/entries")
                .param("materialId", materialId.toString())
                .param("userId", userId.toString())
                .param("locationId", locationId.toString())
                .param("quality", "90")
                .param("missionId", missionId.toString()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-item-id=\"" + itemId + "\"")))
        .andExpect(content().string(containsString("assoc-chip--mission")))
        .andExpect(content().string(containsString("data-target-id=\"" + missionId + "\"")))
        .andExpect(content().string(containsString(missionName)));
  }

  /**
   * The personal Lager renders the collapsed stack row (location, entry count, toggle and lazy
   * entries container) without inlining entry rows (ADR-0003, REQ-INV-002).
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void viewMyInventory_WithStack_ShouldRenderCollapsedStackRow() throws Exception {
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    String locationName = "Port Olisar Hangar 7";

    InventoryStackDto stack =
        new InventoryStackDto(
            new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
            new LocationReferenceDto(locationId, locationName),
            95,
            false,
            null,
            12.5,
            95.0,
            95,
            1);
    GroupedInventoryDto group =
        new GroupedInventoryDto(
            new MaterialReferenceDto(materialId, "Quantanium", "SCU"),
            null,
            12.5,
            95.0,
            95,
            List.of(stack));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/my-inventory/grouped")) {
                return List.of(group);
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/my"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(locationName)))
        .andExpect(content().string(containsString("data-trigger=\"inv-my-toggle-stack\"")))
        .andExpect(content().string(containsString("stack-entry-count")))
        .andExpect(content().string(containsString("data-stack-loaded=\"false\"")))
        .andExpect(content().string(containsString("stack-entries-content")))
        .andExpect(content().string(not(containsString("data-item-id="))));
  }

  /**
   * Same as {@link #viewMyInventory_WithStack_ShouldRenderCollapsedStackRow()} for {@code
   * /inventory/all}: the collapsed stack row with owner renders without inlined entry rows.
   */
  @Test
  @WithMockUser(roles = "LOGISTICIAN", username = "logi-user")
  void viewAllInventory_WithStack_ShouldRenderCollapsedStackRow() throws Exception {
    UUID materialId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    String locationName = "Everus Harbor Storage";
    String ownerName = "Logi Owner";

    InventoryStackDto stack =
        new InventoryStackDto(
            new UserReferenceDto(userId, "owner", "Owner", ownerName, null),
            new LocationReferenceDto(locationId, locationName),
            80,
            false,
            null,
            7.0,
            80.0,
            80,
            1);
    GroupedInventoryDto group =
        new GroupedInventoryDto(
            new MaterialReferenceDto(materialId, "Laranite", "SCU"),
            null,
            7.0,
            80.0,
            80,
            List.of(stack));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/all/grouped")) {
                return List.of(group);
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/all"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString(locationName)))
        .andExpect(content().string(containsString(ownerName)))
        .andExpect(content().string(containsString("data-trigger=\"inv-admin-toggle-stack\"")))
        .andExpect(content().string(containsString("stack-entry-count")))
        .andExpect(content().string(containsString("data-stack-loaded=\"false\"")))
        .andExpect(content().string(containsString("stack-entries-content")));
  }

  /**
   * The book-in form still renders {@code 200} when one parallel catalog lookup fails: the failed
   * {@code missions} attribute is empty and {@code materials} is populated.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_WhenOneCatalogFetchFails_StillRendersWithEmptyList() throws Exception {
    when(backendApiClient.getCached(eq(CachedCatalog.MATERIALS_LOOKUP), anyTypeRef()))
        .thenReturn(List.of(new MaterialReferenceDto(UUID.randomUUID(), "Laranite", "SCU")));
    when(backendApiClient.get(eq("/api/v1/missions/lookup"), anyTypeRef()))
        .thenThrow(new RuntimeException("backend down"));

    mockMvc
        .perform(get("/inventory/input"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-input"))
        .andExpect(model().attribute("missions", empty()))
        .andExpect(model().attribute("materials", hasSize(1)));
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_RendersSplitAtCheckInAllocationControls() throws Exception {
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/input"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-input"))
        .andExpect(content().string(containsString("id=\"jobOrderAllocRows\"")))
        .andExpect(content().string(containsString("id=\"missionAllocRows\"")))
        .andExpect(content().string(containsString("data-trigger=\"inv-input-add-order\"")))
        .andExpect(content().string(containsString("data-trigger=\"inv-input-add-mission\"")))
        .andExpect(content().string(containsString("id=\"jobOrderRowTemplate\"")));
  }

  /** Builds the game-item reference used across the item-view render tests. */
  private static InventoryGameItemReferenceDto sampleGameItem(UUID gameItemId) {
    return new InventoryGameItemReferenceDto(
        gameItemId, "Quantum Drive XL-1", "RSI", "VEHICLE_ITEM");
  }

  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewMyInventory_rendersMaterialItemsViewSwitch() throws Exception {
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/my"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-testid=\"lager-view-material\"")))
        .andExpect(content().string(containsString("data-testid=\"lager-view-items\"")))
        .andExpect(content().string(containsString("/inventory/my?view=items")));
  }

  /**
   * {@code /inventory/my?view=items} renders the game-item tree with whole-unit amounts, without
   * quality or mission filter, and with the item filter bar (REQ-INV-030).
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void viewMyInventory_itemsView_rendersItemTreeWithoutQuality() throws Exception {
    UUID gameItemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    InventoryStackDto stack =
        new InventoryStackDto(
            new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
            new LocationReferenceDto(locationId, "ARC-L1"),
            null,
            false,
            null,
            3.0,
            null,
            null,
            2);
    GroupedInventoryDto group =
        new GroupedInventoryDto(null, sampleGameItem(gameItemId), 3.0, null, null, List.of(stack));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/my-inventory/grouped") && url.contains("catalog=ITEM")) {
                return List.of(group);
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/my").param("view", "items"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-my"))
        .andExpect(content().string(containsString("Quantum Drive XL-1")))
        .andExpect(content().string(containsString("RSI")))
        .andExpect(content().string(containsString("data-game-item-id=\"" + gameItemId + "\"")))
        .andExpect(content().string(containsString("lager-items-tree")))
        .andExpect(content().string(not(containsString("tree-gauge"))))
        .andExpect(content().string(not(containsString("id=\"minQuality\""))))
        .andExpect(content().string(containsString("id=\"gameItemFilterContainer\"")))
        .andExpect(content().string(containsString("id=\"itemJobOrderFilterContainer\"")))
        .andExpect(content().string(containsString("id=\"itemPersonalOnly\"")))
        .andExpect(content().string(not(containsString("id=\"missionFilterContainer\""))))
        .andExpect(content().string(not(containsString("3.000"))));
  }

  /**
   * Item-view render guard for the squadron-wide Lager (REQ-INV-030): {@code
   * /inventory/all?view=items} renders the game-item tree with the per-owner stack key (userId in
   * addition to gameItemId) and the item filter bar, without quality or mission dimensions.
   */
  @Test
  @WithMockUser(roles = "LOGISTICIAN", username = "logi-user")
  void viewAllInventory_itemsView_rendersItemTreeWithoutQuality() throws Exception {
    UUID gameItemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    InventoryStackDto stack =
        new InventoryStackDto(
            new UserReferenceDto(userId, "owner", "Owner", "Logi Owner", null),
            new LocationReferenceDto(locationId, "Everus Harbor Storage"),
            null,
            false,
            null,
            5.0,
            null,
            null,
            1);
    GroupedInventoryDto group =
        new GroupedInventoryDto(null, sampleGameItem(gameItemId), 5.0, null, null, List.of(stack));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/all/grouped") && url.contains("catalog=ITEM")) {
                return List.of(group);
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/all").param("view", "items"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-admin"))
        .andExpect(content().string(containsString("Quantum Drive XL-1")))
        .andExpect(content().string(containsString("data-game-item-id=\"" + gameItemId + "\"")))
        .andExpect(content().string(containsString("data-user-id=\"" + userId + "\"")))
        .andExpect(content().string(containsString("lager-items-tree")))
        .andExpect(content().string(not(containsString("tree-gauge"))))
        .andExpect(content().string(not(containsString("id=\"minQuality\""))))
        .andExpect(content().string(containsString("id=\"gameItemFilterContainer\"")))
        .andExpect(content().string(not(containsString("id=\"missionFilterContainer\""))));
  }

  /**
   * Item-view render guard for the aggregated overview (REQ-INV-030): {@code /inventory?view=items}
   * renders per-item totals — name, manufacturer, localized kind, whole amount — without the
   * material view's quality columns, and its rows navigate to the org-wide item tree pre-filtered
   * to the clicked gameItem.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewAggregatedInventory_itemsView_rendersItemColumnsWithoutQuality() throws Exception {
    UUID gameItemId = UUID.randomUUID();
    AggregatedInventoryDto row =
        new AggregatedInventoryDto(null, sampleGameItem(gameItemId), null, null, 7.0);
    PageResponse<AggregatedInventoryDto> page =
        new PageResponse<>(List.of(row), 0, 10, 1, 1, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory").param("view", "items"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-index"))
        .andExpect(content().string(containsString("Quantum Drive XL-1")))
        .andExpect(content().string(containsString("RSI")))
        .andExpect(
            content()
                .string(containsString("/inventory/all?view=items&amp;gameItemIds=" + gameItemId)))
        .andExpect(content().string(not(containsString("Max. Qualit"))));
  }

  /**
   * Drilldown render guard (REQ-INV-030): {@code /inventory/game-item/{gameItemId}} renders the
   * per-item stock rows (owner, location, whole amount) with the item's display name resolved from
   * the rows, no quality column and — deliberately — no navigate catalog picker (the item catalog
   * is thousands of entries; the remote picker ships with the Einbuchen pass).
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewGameItemInventory_rendersDrilldownWithoutNavigatePicker() throws Exception {
    UUID gameItemId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    InventoryItemDto item =
        new InventoryItemDto(
            UUID.randomUUID(),
            new UserReferenceDto(userId, "tester", "Tester", "Item Owner", null),
            null,
            sampleGameItem(gameItemId),
            new LocationReferenceDto(locationId, "Port Tressler"),
            null,
            4.0,
            false,
            java.util.List.of(),
            null,
            java.util.List.of(),
            null,
            null,
            null,
            1L,
            null,
            Instant.parse("2026-03-01T00:00:00Z"));
    PageResponse<InventoryItemDto> page =
        new PageResponse<>(List.of(item), 0, 1000, 1, 1, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/game-item/" + gameItemId))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-game-item"))
        .andExpect(content().string(containsString("Quantum Drive XL-1")))
        .andExpect(content().string(containsString("Item Owner")))
        .andExpect(content().string(containsString("Port Tressler")))
        .andExpect(content().string(containsString("id=\"inventory-game-item-results\"")))
        .andExpect(content().string(containsString("/js/inventory-game-item.js")))
        .andExpect(content().string(not(containsString("id=\"materialSelect\""))))
        .andExpect(content().string(not(containsString("4.000"))));
  }

  /**
   * The per-game-item drilldown renders the pager and size options for a multi-page backend
   * response (REQ-INV-033, ADR-0104).
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewGameItemInventory_ShouldRenderPaginationControls() throws Exception {
    UUID gameItemId = UUID.randomUUID();
    InventoryItemDto item =
        new InventoryItemDto(
            UUID.randomUUID(),
            new UserReferenceDto(UUID.randomUUID(), "user", "User", "Item Owner", null),
            null,
            sampleGameItem(gameItemId),
            new LocationReferenceDto(UUID.randomUUID(), "Port Tressler"),
            null,
            4.0,
            false,
            java.util.List.of(),
            null,
            java.util.List.of(),
            null,
            null,
            null,
            1L,
            null,
            Instant.parse("2026-03-01T00:00:00Z"));
    PageResponse<InventoryItemDto> page =
        new PageResponse<>(List.of(item), 1, 50, 130, 3, Collections.emptyList());
    when(backendApiClient.get(anyString(), anyTypeRef())).thenReturn(page);
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/game-item/" + gameItemId).param("page", "1"))
        .andExpect(status().isOk())
        .andExpect(view().name("inventory-game-item"))
        .andExpect(content().string(containsString("Port Tressler")))
        .andExpect(content().string(containsString("class=\"pagination\"")))
        .andExpect(content().string(containsString("page=2")))
        .andExpect(content().string(containsString("size=50")))
        .andExpect(content().string(containsString(">200<")));
  }

  /**
   * The game-item stack-entries fragment renders the item leaf row with whole-unit amount, no
   * mission split, the Börse release toggle, and a "+ Zuordnen" picker limited to ITEM orders
   * requesting the entry's game item (REQ-INV-031).
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void viewMyGameItemStackEntries_rendersItemLeafWithoutMissionSplit() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID matchingOrderId = UUID.randomUUID();
    UUID unrelatedOrderId = UUID.randomUUID();

    InventoryItemDto entry =
        new InventoryItemDto(
            itemId,
            new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
            null,
            sampleGameItem(gameItemId),
            new LocationReferenceDto(locationId, "ARC-L1"),
            null,
            2.0,
            false,
            java.util.List.of(),
            2.0,
            java.util.List.of(),
            null,
            null,
            null,
            1L,
            null,
            Instant.parse("2026-03-01T00:00:00Z"));
    JobOrderReferenceDto matching =
        new JobOrderReferenceDto(
            matchingOrderId,
            71,
            "h1",
            "IN_PROGRESS",
            null,
            List.of(),
            List.of(),
            List.of(gameItemId),
            List.of(),
            List.of());
    JobOrderReferenceDto unrelated =
        new JobOrderReferenceDto(
            unrelatedOrderId,
            99,
            "h2",
            "IN_PROGRESS",
            null,
            List.of(),
            List.of(),
            List.of(UUID.randomUUID()),
            List.of(),
            List.of());

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/my-inventory/stack/entries")
                  && url.contains("catalog=ITEM")
                  && url.contains("gameItemId=" + gameItemId)) {
                return new PageResponse<>(List.of(entry), 0, 20, 1, 1, Collections.emptyList());
              }
              if (url.contains("/orders/lookup")) {
                return List.of(matching, unrelated);
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(
            get("/inventory/my/game-item-stack/entries")
                .param("gameItemId", gameItemId.toString())
                .param("locationId", locationId.toString())
                .param("personal", "false"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-item-id=\"" + itemId + "\"")))
        .andExpect(content().string(not(containsString("data-assoc-field=\"MISSION\""))))
        .andExpect(content().string(containsString("inv-boerse-toggle")))
        .andExpect(content().string(containsString("data-kind=\"ITEM\"")))
        .andExpect(content().string(containsString("data-boerse-status-for=\"" + itemId + "\"")))
        .andExpect(content().string(not(containsString("checked=\"checked\""))))
        .andExpect(content().string(containsString("data-quantity-type=\"PIECE\"")))
        .andExpect(content().string(containsString("data-game-item-id=\"" + gameItemId + "\"")))
        .andExpect(content().string(not(containsString("data-material-id"))))
        .andExpect(content().string(containsString("value=\"" + matchingOrderId + "\"")))
        .andExpect(content().string(not(containsString("value=\"" + unrelatedOrderId + "\""))));
  }

  /**
   * Item-leaf "Für Börse freigeben" toggle checked state (REQ-MARKET-002/014): when the batch
   * released-item-ids lookup reports the row as released, the item leaf's toggle renders checked
   * with the "Auf Börse" chip ({@code chip--primary}) — the item sibling of the material leaf
   * toggle for a stock-backed item offer.
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
  void viewMyGameItemStackEntries_rendersBoerseToggleCheckedWhenReleased() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    InventoryItemDto entry =
        new InventoryItemDto(
            itemId,
            new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
            null,
            sampleGameItem(gameItemId),
            new LocationReferenceDto(locationId, "ARC-L1"),
            null,
            2.0,
            false,
            java.util.List.of(),
            2.0,
            java.util.List.of(),
            null,
            null,
            null,
            1L,
            null,
            Instant.parse("2026-03-01T00:00:00Z"));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/my-inventory/stack/entries")
                  && url.contains("catalog=ITEM")
                  && url.contains("gameItemId=" + gameItemId)) {
                return new PageResponse<>(List.of(entry), 0, 20, 1, 1, Collections.emptyList());
              }
              if (url.contains("/material-exchange/released-item-ids")) {
                return List.of(itemId);
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(
            get("/inventory/my/game-item-stack/entries")
                .param("gameItemId", gameItemId.toString())
                .param("locationId", locationId.toString())
                .param("personal", "false"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("inv-boerse-toggle")))
        .andExpect(content().string(containsString("checked=\"checked\"")))
        .andExpect(content().string(containsString("chip--primary")))
        .andExpect(content().string(containsString("data-boerse-status-for=\"" + itemId + "\"")));
  }

  /**
   * Admin variant of the item stack-entries fragment (REQ-INV-030): {@code
   * /inventory/all/game-item-stack/entries} addresses the per-owner stack by gameItemId + userId
   * and renders the same quality-less, mission-less item leaf with the admin trigger set.
   */
  @Test
  @WithMockUser(roles = "LOGISTICIAN", username = "logi-user")
  void viewAllGameItemStackEntries_rendersItemLeaf() throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    InventoryItemDto entry =
        new InventoryItemDto(
            itemId,
            new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
            null,
            sampleGameItem(gameItemId),
            new LocationReferenceDto(locationId, "ARC-L1"),
            null,
            2.0,
            false,
            java.util.List.of(),
            2.0,
            java.util.List.of(),
            null,
            null,
            null,
            1L,
            null,
            Instant.parse("2026-03-01T00:00:00Z"));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/all/stack/entries")
                  && url.contains("catalog=ITEM")
                  && url.contains("gameItemId=" + gameItemId)
                  && url.contains("userId=" + userId)) {
                return new PageResponse<>(List.of(entry), 0, 20, 1, 1, Collections.emptyList());
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(
            get("/inventory/all/game-item-stack/entries")
                .param("gameItemId", gameItemId.toString())
                .param("userId", userId.toString())
                .param("locationId", locationId.toString()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("data-item-id=\"" + itemId + "\"")))
        .andExpect(content().string(not(containsString("data-assoc-field=\"MISSION\""))))
        .andExpect(content().string(containsString("data-trigger=\"inv-admin-bookout\"")))
        .andExpect(content().string(containsString("data-quantity-type=\"PIECE\"")))
        .andExpect(content().string(not(containsString("data-material-id"))));
  }

  /**
   * The embedded check-in blob's {@code gameItems} half carries each ITEM order's outstanding units
   * (REQ-INV-039).
   */
  @Test
  @WithMockUser(roles = "KRT_MEMBER")
  void viewInputPage_ShouldEmbedTheGameItemNeedFigures() throws Exception {
    UUID gameItemId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();

    JobOrderReferenceDto order =
        new JobOrderReferenceDto(
            orderId,
            71,
            "craft-1",
            "IN_PROGRESS",
            null,
            List.of(),
            List.of(),
            List.of(gameItemId),
            List.of(),
            List.of(new JobOrderGameItemNeedDto(gameItemId, 10, 2, 3, 5)));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              return url.contains("/orders/lookup") ? List.of(order) : Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get("/inventory/input"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("&quot;gameItemId&quot;")))
        .andExpect(content().string(containsString("&quot;outstandingAmount&quot;:5")))
        .andExpect(content().string(not(containsString("&quot;qualityFloor&quot;"))));
  }

  /**
   * The game-item "+ Zuordnen" picker labels each ITEM order with the units it still needs
   * (REQ-INV-039).
   */
  @Test
  @WithMockUser(roles = "LOGISTICIAN", username = "logi-user")
  void viewAllGameItemStackEntries_ShouldLabelOrderOptionsWithTheOutstandingNeed()
      throws Exception {
    UUID itemId = UUID.randomUUID();
    UUID gameItemId = UUID.randomUUID();
    UUID locationId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();

    InventoryItemDto entry =
        new InventoryItemDto(
            itemId,
            new UserReferenceDto(userId, "tester", "Tester", "Tester", null),
            null,
            sampleGameItem(gameItemId),
            new LocationReferenceDto(locationId, "ARC-L1"),
            null,
            2.0,
            false,
            java.util.List.of(),
            2.0,
            java.util.List.of(),
            null,
            null,
            null,
            1L,
            null,
            Instant.parse("2026-03-01T00:00:00Z"));

    JobOrderReferenceDto order =
        new JobOrderReferenceDto(
            orderId,
            1042,
            "craft-1",
            "IN_PROGRESS",
            null,
            List.of(),
            List.of(),
            List.of(gameItemId),
            List.of(),
            List.of(new JobOrderGameItemNeedDto(gameItemId, 10, 2, 3, 5)));

    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            inv -> {
              String url = inv.getArgument(0);
              if (url.contains("/inventory/all/stack/entries") && url.contains("catalog=ITEM")) {
                return new PageResponse<>(List.of(entry), 0, 20, 1, 1, Collections.emptyList());
              }
              if (url.contains("/orders/lookup")) {
                return List.of(order);
              }
              return Collections.emptyList();
            });
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(
            get("/inventory/all/game-item-stack/entries")
                .param("gameItemId", gameItemId.toString())
                .param("userId", userId.toString())
                .param("locationId", locationId.toString()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("#1042 " + NEED_SEPARATOR + " noch 5")))
        .andExpect(content().string(containsString("value=\"" + orderId + "\"")));
  }

  /**
   * Both Lager pages offer a location multi-select in both views, populated from the stocked
   * locations of the grouped result (REQ-INV-040).
   *
   * @param path the Lager view under test
   * @throws Exception if the request fails
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/inventory/my",
        "/inventory/my?view=items",
        "/inventory/all",
        "/inventory/all?view=items"
      })
  @WithMockUser(roles = "ADMIN")
  void lagerViews_renderTheLocationFilterWithInScopeOptions(String path) throws Exception {
    UUID locationId = UUID.randomUUID();
    LocationReferenceDto location = new LocationReferenceDto(locationId, "Everus Harbor");
    InventoryStackDto stack =
        new InventoryStackDto(
            new UserReferenceDto(UUID.randomUUID(), "pilot", null, null, null),
            location,
            700,
            false,
            null,
            10.0,
            700.0,
            700,
            1);
    boolean itemsView = path.contains("view=items");
    GroupedInventoryDto group =
        itemsView
            ? new GroupedInventoryDto(
                null,
                new InventoryGameItemReferenceDto(
                    UUID.randomUUID(), "Quantum Drive", "RSI", "SHIP_ITEM"),
                10.0,
                null,
                null,
                List.of(stack))
            : new GroupedInventoryDto(
                new MaterialReferenceDto(UUID.randomUUID(), "Quantanium", "SCU"),
                null,
                10.0,
                700.0,
                700,
                List.of(stack));
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(
            invocation ->
                ((String) invocation.getArgument(0)).contains("/grouped")
                    ? List.of(group)
                    : Collections.emptyList());
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());

    mockMvc
        .perform(get(path))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("name=\"locationIds\"")))
        .andExpect(content().string(containsString("class=\"locCheck\"")))
        .andExpect(content().string(containsString("Everus Harbor")))
        .andExpect(content().string(containsString("Standort")));
  }
}
